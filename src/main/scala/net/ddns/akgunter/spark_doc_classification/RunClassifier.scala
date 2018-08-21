package net.ddns.akgunter.spark_doc_classification

import java.nio.file.Paths

import org.apache.spark.ml.Pipeline
import org.apache.spark.ml.classification.MultilayerPerceptronClassifier
import org.apache.spark.ml.evaluation.MulticlassClassificationEvaluator
import org.apache.spark.ml.feature.{Binarizer, ChiSqSelector, IDF, VectorSlicer}
import org.apache.spark.sql.SparkSession
import org.deeplearning4j.eval.Evaluation
import org.deeplearning4j.nn.conf.NeuralNetConfiguration
import org.deeplearning4j.nn.conf.layers.{DenseLayer, OutputLayer}
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork
import org.deeplearning4j.nn.weights.WeightInit
import org.deeplearning4j.optimize.listeners.ScoreIterationListener
import org.deeplearning4j.spark.api.RDDTrainingApproach
import org.deeplearning4j.spark.impl.multilayer.SparkDl4jMultiLayer
import org.deeplearning4j.spark.impl.paramavg.ParameterAveragingTrainingMaster
import org.nd4j.linalg.activations.Activation
import org.nd4j.linalg.learning.config.Nesterovs
import org.nd4j.linalg.lossfunctions.LossFunctions

import net.ddns.akgunter.spark_doc_classification.spark.CanSpark
import net.ddns.akgunter.spark_doc_classification.sparkml_processing.{CommonElementFilter, WordCountToVec}
import net.ddns.akgunter.spark_doc_classification.util.DataFrameUtil._
import net.ddns.akgunter.spark_doc_classification.util.DataSetUtil._
import net.ddns.akgunter.spark_doc_classification.util.FileUtil._

object RunMode extends Enumeration {
  val PREPROCESS, SPARKML, DL4J, DL4JDEEP, DL4JSPARK = Value
}

object RunClassifier extends CanSpark {
  def runPreprocess(trainingDir: String, validationDir: String, outputDataDir: String)(implicit spark: SparkSession): Unit = {
    val commonElementFilter = new CommonElementFilter()
      .setDropFreq(0.1)
    val wordVectorizer = new WordCountToVec()

    val rawWordVectorColParam = wordVectorizer.getParam("vectorCol")
    val rawWordVectorCol = wordVectorizer.getOrDefault(rawWordVectorColParam).asInstanceOf[String]
    val vectorSlicer = new VectorSlicer()
      .setInputCol(rawWordVectorCol)
      .setOutputCol("sliced_vector")
      .setIndices((0 until 10).toArray)
    val binarizer = new Binarizer()
      .setThreshold(0.0)
      .setInputCol("raw_word_vector")
      .setOutputCol("binarized_word_vector")
    val idf = new IDF()
      .setInputCol("binarized_word_vector")
      .setOutputCol("tfidf_vector")
      .setMinDocFreq(2)
    val chiSel = new ChiSqSelector()
      .setFeaturesCol("tfidf_vector")
      .setLabelCol("label")
      .setOutputCol("chi_sel_features")
      .setNumTopFeatures(8000)

    val preprocStages = Array(commonElementFilter, wordVectorizer, binarizer, idf, chiSel)
    val preprocPipeline = new Pipeline().setStages(preprocStages)


    logger.info("Loading data...")
    val trainingData = dataFrameFromRawDirectory(trainingDir, isLabelled = true)
    val validationData = dataFrameFromRawDirectory(validationDir, isLabelled = true)

    logger.info("Fitting preprocessing pipeline...")
    val preprocModel = preprocPipeline.fit(trainingData)


    logger.info("Preprocessing data...")
    val trainingDataSparse = preprocModel.transform(trainingData)
    val validationDataSparse = preprocModel.transform(validationData)


    val lastStage = preprocPipeline.getStages.last
    val pipeFeaturesColParam = lastStage.getParam("outputCol")
    val pipeFeaturesCol = lastStage.getOrDefault(pipeFeaturesColParam).asInstanceOf[String]
    val pipeLabelColParam = wordVectorizer.getParam("labelCol")
    val pipeLabelCol = wordVectorizer.getOrDefault(pipeLabelColParam).asInstanceOf[String]


    val trainingDataFilePath = Paths.get(outputDataDir, TrainingDirName).toString
    val validationDataFilePath = Paths.get(outputDataDir, ValidationDirName).toString

    logger.info("Writing training data to CSV...")
    val trainingDataCSVReady = sparseDFToCSVReadyDF(trainingDataSparse, pipeFeaturesCol, pipeLabelCol)
    trainingDataCSVReady.write
      .mode("overwrite")
      .csv(trainingDataFilePath)

    logger.info("Writing validation data to CSV...")
    val validationDataCSVReady = sparseDFToCSVReadyDF(validationDataSparse, pipeFeaturesCol, pipeLabelCol)
    validationDataCSVReady.write
    .mode("overwrite")
    .csv(validationDataFilePath)
  }

  def runSparkML(trainingDir: String, validationDir: String, numEpochs: Int)(implicit spark: SparkSession): Unit = {
    logger.info("Loading data files...")
    val trainingDataCSVSourced = dataFrameFromProcessedDirectory(trainingDir)
    val validationDataCSVSourced = dataFrameFromProcessedDirectory(validationDir)


    logger.info("Creating data sets...")
    val trainingData = sparseDFFromCSVReadyDF(trainingDataCSVSourced)
    val validationData = sparseDFFromCSVReadyDF(validationDataCSVSourced)


    val Array(csvNumFeaturesCol, _, _, csvLabelCol) = trainingDataCSVSourced.columns
    val numFeatures = trainingDataCSVSourced.head.getAs[Int](csvNumFeaturesCol)
    val numClasses = trainingData.select(csvLabelCol).distinct.count.toInt

    logger.info(s"Configuring neural net with $numFeatures features and $numClasses classes...")
    val Array(sparseFeaturesCol, sparseLabelsCol) = SchemaForSparseDataFrames.fieldNames
    val mlpc = new MultilayerPerceptronClassifier()
      .setLayers(Array(numFeatures, numClasses))
      .setMaxIter(numEpochs)
      //.setBlockSize(20)
      .setFeaturesCol(sparseFeaturesCol)
      .setLabelCol(sparseLabelsCol)


    logger.info("Training neural network...")
    val mlpcModel = mlpc.fit(trainingData)


    logger.info("Calculating predictions...")
    val trainingPredictions = mlpcModel.transform(trainingData)
    val validationPredictions = mlpcModel.transform(validationData)

    val accuracyEvaluator = new MulticlassClassificationEvaluator()
      .setMetricName("accuracy")
    val precisionEvaluator = new MulticlassClassificationEvaluator()
      .setMetricName("weightedPrecision")
    val recallEvaluator = new MulticlassClassificationEvaluator()
      .setMetricName("weightedRecall")
    val f1Evaluator = new  MulticlassClassificationEvaluator()
      .setMetricName("f1")

    logger.info(s"Training accuracy: ${accuracyEvaluator.evaluate(trainingPredictions)}")
    logger.info(s"Training precision: ${precisionEvaluator.evaluate(trainingPredictions)}")
    logger.info(s"Training recall: ${recallEvaluator.evaluate(trainingPredictions)}")
    logger.info(s"Training F1: ${f1Evaluator.evaluate(trainingPredictions)}")

    logger.info(s"Validation accuracy: ${accuracyEvaluator.evaluate(validationPredictions)}")
    logger.info(s"Validation precision: ${precisionEvaluator.evaluate(validationPredictions)}")
    logger.info(s"Validation recall: ${recallEvaluator.evaluate(validationPredictions)}")
    logger.info(s"Validation F1: ${f1Evaluator.evaluate(validationPredictions)}")
  }

  def runDL4J(trainingDir: String, validationDir: String, numEpochs: Int): Unit = {
    val (trainingDataSet, validationDataSet, numFeatures, numClasses) = withSpark() {
      spark =>
        logger.info("Loading data files...")
        val trainingDataCSVSourced = dataFrameFromProcessedDirectory(trainingDir)(spark)
        val validationDataCSVSourced = dataFrameFromProcessedDirectory(validationDir)(spark)

        logger.info("Creating data sets...")
        val trainingDataSparse = sparseDFFromCSVReadyDF(trainingDataCSVSourced)
        val validationDataSparse = sparseDFFromCSVReadyDF(validationDataCSVSourced)

        val Array(csvNumFeaturesCol, _, _, csvLabelCol) = trainingDataCSVSourced.columns
        val numFeatures = trainingDataCSVSourced.head.getAs[Int](csvNumFeaturesCol)
        val numClasses = trainingDataSparse.select(csvLabelCol).distinct.count.toInt

        val trainingRDD = dl4jRDDFromSparseDataFrame(trainingDataSparse, numClasses)
        val validationRDD = dl4jRDDFromSparseDataFrame(validationDataSparse, numClasses)

        val trainingDataSet = dataSetFromdl4jRDD(trainingRDD)
        val validationDataSet = dataSetFromdl4jRDD(validationRDD)

        (trainingDataSet, validationDataSet, numFeatures, numClasses)
    }

    logger.info(s"Configuring neural net with $numFeatures features and $numClasses classes...")
    val nnConf = new NeuralNetConfiguration.Builder()
      .activation(Activation.LEAKYRELU)
      .weightInit(WeightInit.XAVIER)
      .updater(new Nesterovs(0.02))
      .l2(1e-4)
      .list()
      .layer(0, new DenseLayer.Builder().nIn(numFeatures).nOut(numClasses).build)
      .layer(1, new OutputLayer.Builder(LossFunctions.LossFunction.NEGATIVELOGLIKELIHOOD)
        .activation(Activation.SOFTMAX).nIn(numClasses).nOut(numClasses).build)
      .pretrain(false)
      .backprop(true)
      .build

    val network = new MultiLayerNetwork(nnConf)
    network.init()
    network.setListeners(new ScoreIterationListener(10))

    logger.info("Training neural network...")
    0 until numEpochs foreach {
      epoch =>
        if (epoch % 5 == 0) logger.info(s"Running epoch $epoch...")
        network.fit(trainingDataSet)
    }


    logger.info("Evaluating performance...")
    val eval = new Evaluation()
    eval.eval(trainingDataSet.getLabels, trainingDataSet.getFeatureMatrix, network)
    logger.info(eval.stats)

    eval.eval(validationDataSet.getLabels, validationDataSet.getFeatureMatrix, network)
    logger.info(eval.stats)
  }

  def runDL4JDeep(trainingDir: String, validationDir: String, numEpochs: Int): Unit = {
    val (trainingDataSet, validationDataSet, numFeatures, numClasses) = withSpark() {
      spark =>
        logger.info("Loading data files...")
        val trainingDataCSVSourced = dataFrameFromProcessedDirectory(trainingDir)(spark)
        val validationDataCSVSourced = dataFrameFromProcessedDirectory(validationDir)(spark)

        logger.info("Creating data sets...")
        val trainingDataSparse = sparseDFFromCSVReadyDF(trainingDataCSVSourced)
        val validationDataSparse = sparseDFFromCSVReadyDF(validationDataCSVSourced)

        val Array(csvNumFeaturesCol, _, _, csvLabelCol) = trainingDataCSVSourced.columns
        val numFeatures = trainingDataCSVSourced.head.getAs[Int](csvNumFeaturesCol)
        val numClasses = trainingDataSparse.select(csvLabelCol).distinct.count.toInt

        val trainingRDD = dl4jRDDFromSparseDataFrame(trainingDataSparse, numClasses)
        val validationRDD = dl4jRDDFromSparseDataFrame(validationDataSparse, numClasses)

        val trainingDataSet = dataSetFromdl4jRDD(trainingRDD)
        val validationDataSet = dataSetFromdl4jRDD(validationRDD)

        (trainingDataSet, validationDataSet, numFeatures, numClasses)
    }

    logger.info(s"Configuring neural net with $numFeatures features and $numClasses classes...")
    val midLayerSize = numFeatures/2
    val nnConf = new NeuralNetConfiguration.Builder()
      .activation(Activation.LEAKYRELU)
      .weightInit(WeightInit.XAVIER)
      .updater(new Nesterovs(0.02))
      .l2(1e-4)
      .list()
      .layer(0, new DenseLayer.Builder().nIn(numFeatures).nOut(midLayerSize).build)
      .layer(1, new DenseLayer.Builder().nIn(midLayerSize).nOut(numClasses).build)
      .layer(2, new OutputLayer.Builder(LossFunctions.LossFunction.NEGATIVELOGLIKELIHOOD)
        .activation(Activation.SOFTMAX).nIn(numClasses).nOut(numClasses).build)
      .pretrain(false)
      .backprop(true)
      .build

    val network = new MultiLayerNetwork(nnConf)
    network.init()
    network.setListeners(new ScoreIterationListener(10))

    logger.info("Training neural network...")
    0 until numEpochs foreach {
      epoch =>
        if (epoch % 5 == 0) logger.info(s"Running epoch $epoch...")
        network.fit(trainingDataSet)
    }


    logger.info("Evaluating performance...")
    val eval = new Evaluation()
    eval.eval(trainingDataSet.getLabels, trainingDataSet.getFeatureMatrix, network)
    logger.info(eval.stats)

    eval.eval(validationDataSet.getLabels, validationDataSet.getFeatureMatrix, network)
    logger.info(eval.stats)
  }

  def runDL4JSpark(trainingDir: String, validationDir: String, numEpochs: Int)(implicit spark: SparkSession): Unit = {
    logger.info("Loading data files...")
    val trainingDataCSVSourced = dataFrameFromProcessedDirectory(trainingDir)
    val validationDataCSVSourced = dataFrameFromProcessedDirectory(validationDir)


    logger.info("Creating data sets...")
    val trainingDataSparse = sparseDFFromCSVReadyDF(trainingDataCSVSourced)
    val validationDataSparse = sparseDFFromCSVReadyDF(validationDataCSVSourced)

    val Array(csvNumFeaturesCol, _, _, csvLabelCol) = trainingDataCSVSourced.columns
    val numFeatures = trainingDataCSVSourced.head.getAs[Int](csvNumFeaturesCol)
    val numClasses = trainingDataSparse.select(csvLabelCol).distinct.count.toInt

    val trainingRDD = dl4jRDDFromSparseDataFrame(trainingDataSparse, numClasses)
    val validationRDD = dl4jRDDFromSparseDataFrame(validationDataSparse, numClasses)


    logger.info(s"Configuring neural net with $numFeatures features and $numClasses classes...")
    val nnConf = new NeuralNetConfiguration.Builder()
      .activation(Activation.LEAKYRELU)
      .weightInit(WeightInit.XAVIER)
      .updater(new Nesterovs(0.02))
      .l2(1e-4)
      .list()
      .layer(0, new DenseLayer.Builder().nIn(numFeatures).nOut(numClasses).build)
      .layer(1, new OutputLayer.Builder(LossFunctions.LossFunction.NEGATIVELOGLIKELIHOOD)
        .activation(Activation.SOFTMAX).nIn(numClasses).nOut(numClasses).build)
      .pretrain(false)
      .backprop(true)
      .build

    val trainingMaster = new ParameterAveragingTrainingMaster.Builder(1)
      .rddTrainingApproach(RDDTrainingApproach.Export)
      .exportDirectory("/tmp/alex-spark/SparkLearning")
      .build

    val sparkNet = new SparkDl4jMultiLayer(spark.sparkContext, nnConf, trainingMaster)


    logger.info("Training neural network...")
    0 until numEpochs foreach {
      epoch =>
        if (epoch % 5 == 0) logger.info(s"Running epoch $epoch...")
        sparkNet.fit(trainingRDD)
    }


    logger.info("Evaluating performance...")
    val trainingEval = sparkNet.doEvaluation(trainingRDD, new Evaluation(numClasses), 4)
    logger.info(trainingEval.stats)

    val validationEval = sparkNet.doEvaluation(validationRDD, new Evaluation(numClasses), 4)
    logger.info(validationEval.stats)
  }

  case class Config(
                     runMode: RunMode.Value = RunMode.PREPROCESS,
                     inputDataDir: String = null,
                     outputDataDir: String = null,
                     numEpochs: Int = 0
                   )

  def getOptionParser: scopt.OptionParser[Config] = {
    new scopt.OptionParser[Config]("DocClassifier") {
      cmd(RunMode.PREPROCESS.toString)
        .action( (_, c) => c.copy(runMode = RunMode.PREPROCESS) )
        .text("Run the program in PREPROCESS mode")
        .children(
          arg[String]("<inputDataDir>")
            .action( (x, c) => c.copy(inputDataDir = x))
            .text("The file path to the input data"),
          arg[String]("<outputDataDir>")
            .action( (x, c) => c.copy(outputDataDir = x) )
            .text("The file path to write preprocessed data to")
        )

      cmd(RunMode.SPARKML.toString)
        .action( (_, c) => c.copy(runMode = RunMode.SPARKML) )
        .text("Run the program in SPARKML mode")
        .children(
          arg[String]("<inputDataDir>")
            .action( (x, c) => c.copy(inputDataDir = x))
            .text("The file path to the input data"),
          arg[Int]("<numEpochs>")
            .action( (x, c) => c.copy(numEpochs = x) )
            .text("The number of epochs to run")
        )

      cmd(RunMode.DL4J.toString)
        .action( (_, c) => c.copy(runMode = RunMode.DL4J) )
        .text("Run the program in DL4J mode")
        .children(
          arg[String]("<inputDataDir>")
            .action( (x, c) => c.copy(inputDataDir = x))
            .text("The file path to the input data"),
          arg[Int]("<numEpochs>")
            .action( (x, c) => c.copy(numEpochs = x) )
            .text("The number of epochs to run")
        )

      cmd(RunMode.DL4JDEEP.toString)
        .action( (_, c) => c.copy(runMode = RunMode.DL4JDEEP) )
        .text("Run the program in DL4JDEEP mode")
        .children(
          arg[String]("<inputDataDir>")
            .action( (x, c) => c.copy(inputDataDir = x))
            .text("The file path to the input data"),
          arg[Int]("<numEpochs>")
            .action( (x, c) => c.copy(numEpochs = x) )
            .text("The number of epochs to run")
        )

      cmd(RunMode.DL4JSPARK.toString)
        .action( (_, c) => c.copy(runMode = RunMode.DL4JSPARK) )
        .text("Run the program in DL4JSPARK mode")
        .children(
          arg[String]("<inputDataDir>")
            .action( (x, c) => c.copy(inputDataDir = x))
            .text("The file path to the input data"),
          arg[Int]("<numEpochs>")
            .action( (x, c) => c.copy(numEpochs = x) )
            .text("The number of epochs to run")
        )
    }
  }

  def main(args: Array[String]): Unit = {
    val parser = getOptionParser
    parser.parse(args, Config()) match {
      case Some(config) =>
        val trainingDir = Paths.get(config.inputDataDir, TrainingDirName).toString
        val validationDir = Paths.get(config.inputDataDir, ValidationDirName).toString

        config.runMode match {
          case RunMode.PREPROCESS =>
            withSpark() { spark => runPreprocess(trainingDir, validationDir, config.outputDataDir)(spark) }
          case RunMode.SPARKML =>
            withSpark() { spark => runSparkML(trainingDir, validationDir, config.numEpochs)(spark) }
          case RunMode.DL4J =>
            runDL4J(trainingDir, validationDir, config.numEpochs)
          case RunMode.DL4JDEEP =>
            runDL4JDeep(trainingDir, validationDir, config.numEpochs)
          case RunMode.DL4JSPARK =>
            withSpark() { spark => runDL4JSpark(trainingDir, validationDir, config.numEpochs)(spark) }
        }
      case _ =>
    }


  }
}