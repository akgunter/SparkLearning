package net.ddns.akgunter.spark_learning.document_classifier

import java.nio.file.Paths

import org.apache.spark.ml.classification.MultilayerPerceptronClassifier
import org.apache.spark.ml.evaluation.MulticlassClassificationEvaluator
import org.apache.spark.ml.feature.{ChiSqSelector, IDF, PCA}
import org.apache.spark.ml.linalg.SparseVector
import org.apache.spark.ml.{Estimator, Pipeline}
import org.apache.spark.sql.SparkSession

import net.ddns.akgunter.spark_learning.data_processing._
import net.ddns.akgunter.spark_learning.spark.CanSpark
import net.ddns.akgunter.spark_learning.util.FileUtil._

object RunClassifier extends CanSpark {

  def sparkMLTest(dataDir: String)(implicit spark: SparkSession): Unit = {
    val trainingDir = Paths.get(dataDir, "Training").toString
    val validationDir = Paths.get(dataDir, "Validation").toString
    //val testingDir = Paths.get(dataDir, "Testing").toString

    val numClasses = getLabelDirectories(trainingDir).length

    val commonElementFilter = new CommonElementFilter()
      .setDropFreq(0.2)
    val wordVectorizer = new WordCountToVec()
    val idf = new IDF()
      .setInputCol("raw_word_vector")
      .setOutputCol("tfidf_vector")
      .setMinDocFreq(2)
    val chiSel = new ChiSqSelector()
      .setFeaturesCol("tfidf_vector")
      .setLabelCol("label")
      .setOutputCol("chi_sel_features")
      .setSelectorType("fpr")
      .setFpr(0.05)
    val pca = new PCA()
      .setInputCol("chi_sel_features")
      .setK(8000)
      .setOutputCol("pca_features")

    val preprocPipeline = new Pipeline()
        .setStages(Array(commonElementFilter, wordVectorizer, idf, chiSel, pca))

    logger.info("Loading data...")
    val trainingData = dataFrameFromDirectory(trainingDir, training = true)
    val validationData = dataFrameFromDirectory(validationDir, training = true)
    //val testingData = dataFrameFromDirectory(testingDir, training = false)

    logger.info("Fitting preprocessing pipeline...")
    val dataModel = preprocPipeline.fit(trainingData)

    logger.info("Preprocessing data...")
    val trainingDataProcessed = dataModel.transform(trainingData)
    val validationDataProcessed = dataModel.transform(validationData)

    val lastStage = preprocPipeline.getStages.last
    val featuresColParam = lastStage.getParam("outputCol")
    val featuresCol = lastStage.getOrDefault(featuresColParam).asInstanceOf[String]

    val numFeatures = trainingDataProcessed.head.getAs[SparseVector](featuresCol).size

    logger.info(s"Configuring neural net with $numFeatures features and $numClasses classes...")
    val mlpc = new MultilayerPerceptronClassifier()
      .setLayers(Array(numFeatures, numClasses))
      .setMaxIter(100)
      //.setBlockSize(20)
      .setFeaturesCol(featuresCol)

    logger.info("Training neural network...")
    val mlpcModel = mlpc.fit(trainingDataProcessed)

    logger.info("Calculating predictions...")
    val trainingPredictions = mlpcModel.transform(trainingDataProcessed)
    val validationPredictions = mlpcModel.transform(validationDataProcessed)

    val evaluator = new MulticlassClassificationEvaluator()
      .setMetricName("accuracy")

    logger.info(s"Training accuracy: ${evaluator.evaluate(trainingPredictions)}")
    logger.info(s"Validation accuracy: ${evaluator.evaluate(validationPredictions)}")
  }

  def main(args: Array[String]): Unit = {
    val dataDir = args(0)

    println(s"Running with dataDir=$dataDir")
    withSpark() { spark => sparkMLTest(dataDir)(spark) }
  }
}