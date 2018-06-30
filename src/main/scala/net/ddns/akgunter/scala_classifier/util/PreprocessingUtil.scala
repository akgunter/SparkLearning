package net.ddns.akgunter.scala_classifier.util

import org.apache.spark.sql.DataFrame
import org.apache.spark.ml.linalg.{Vectors, Vector}

import net.ddns.akgunter.scala_classifier.lib._
import net.ddns.akgunter.scala_classifier.models.DataPoint
import net.ddns.akgunter.scala_classifier.models.WordIndex

object PreprocessingUtil {

  def vectorize(point: DataPoint,
                wordIndex: WordIndex): SparseVector[Int] = {

    val vector = wordIndex.wordOrdering.map(point.toMap.getOrElse(_, 0))
    SparseVector.fromVector(vector)
  }

  def buildSparseMatrix(dataSet: Array[DataPoint],
                        wordIndex: WordIndex): SparseMatrix[Int] = {

    val vectorList = dataSet.map(vectorize(_, wordIndex))
    SparseMatrix.fromSparseVectors(vectorList)
  }

  def calcTF(vector: SparseVector[Int]): SparseVector[Double] = {
    val wordsInRow = vector.sum.toDouble
    val newVector = vector.vector.map { case (k, v) => k -> v / wordsInRow }
    SparseVector(newVector, vector.length)
  }

  def calcIDF(dataMatrix: SparseMatrix[Int]): SparseVector[Double] = {
    val mtrx = dataMatrix.transpose.table.map {
      case (k, col) =>
        val numDocs = col.count(_ != 0).toDouble
          k -> -Math.log10(numDocs / (dataMatrix.length + numDocs))
    }

    SparseVector(mtrx, dataMatrix.width)
  }

  def calcTFIDF(dataMatrix: SparseMatrix[Int]): SparseMatrix[Double] = {
    val idfVector = calcIDF(dataMatrix)
    calcTFIDF(dataMatrix, idfVector)
  }

  def calcTFIDF(dataMatrix: SparseMatrix[Int],
                idfVector: SparseVector[Double]): SparseMatrix[Double] = {

    val tfMatrix = dataMatrix.table.map { case (k, v) => k -> calcTF(v) }
    val tfidfMatrix = tfMatrix.map { case (k, v) => k -> v ** idfVector }

    SparseMatrix(tfidfMatrix, dataMatrix.shape)
  }
}