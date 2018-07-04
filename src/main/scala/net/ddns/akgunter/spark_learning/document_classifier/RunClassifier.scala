package net.ddns.akgunter.spark_learning.document_classifier

import java.nio.file.Paths

import org.apache.spark.ml.classification.MultilayerPerceptronClassifier
import org.apache.spark.ml.evaluation.MulticlassClassificationEvaluator
import org.apache.spark.ml.feature.{ChiSqSelector, IDF, PCA}
import org.apache.spark.ml.linalg.SparseVector
import org.apache.spark.ml.Pipeline

import org.apache.spark.sql.SparkSession
import net.ddns.akgunter.spark_learning.models.{WordCountToVec, _}
import net.ddns.akgunter.spark_learning.spark.CanSpark
import net.ddns.akgunter.spark_learning.util.FileUtil._

object RunClassifier extends CanSpark {

  def sparkMLTest(dataDir: String)(implicit spark: SparkSession): Unit = {
    val trainingDir = Paths.get(dataDir, "Training").toString
    val validationDir = Paths.get(dataDir, "Validation").toString
    //val testingDir = Paths.get(dataDir, "Testing").toString

    val numClasses = getLabelDirectories(trainingDir).length

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
      .setK(100)
      .setOutputCol("pca_features")

    val preprocPipeline = new Pipeline()
        .setStages(Array(wordVectorizer, idf, chiSel))

    logger.info("Loading data...")
    val trainingData = dataFrameFromDirectory(trainingDir, training = true)
    val validationData = dataFrameFromDirectory(validationDir, training = true)
    //val testingData = dataFrameFromDirectory(testingDir, training = false)

    logger.info("Fitting preprocessing pipeline...")
    val dataModel = preprocPipeline.fit(trainingData)

    logger.info("Preprocessing data...")
    val trainingDataProcessed = dataModel.transform(trainingData)
    val validationDataProcessed = dataModel.transform(validationData)

    logger.info("Configuring neural network...")
    trainingDataProcessed.printSchema()
    val numFeatures = trainingDataProcessed.head.getAs[SparseVector]("chi_sel_features").size

    val mlpc = new MultilayerPerceptronClassifier()
      .setLayers(Array(numFeatures, numClasses))
      .setMaxIter(100)
      //.setBlockSize(20)
      .setFeaturesCol("pca_features")

    /*
    logger.info("Training neural network...")

    logger.info("Calculating predictions...")
    val trainingPredictions = model.transform(trainingData)
    val validationPredictions = model.transform(validationData)


    val evaluator = new MulticlassClassificationEvaluator()
      .setMetricName("accuracy")

    logger.info(s"Training accuracy: ${evaluator.evaluate(trainingPredictions)}")
    logger.info(s"Validation accuracy: ${evaluator.evaluate(validationPredictions)}")
    */
  }

  def main(args: Array[String]): Unit = {
    val dataDir = args(0)

    println(s"Running with dataDir=$dataDir")
    withSpark() { spark => sparkMLTest(dataDir)(spark) }
  }
}