package net.ddns.akgunter.spark_learning.data_processing

import org.apache.spark.ml.Estimator
import org.apache.spark.ml.param.{Param, ParamMap}
import org.apache.spark.sql.Dataset
import org.apache.spark.sql.types.StructType
import org.apache.spark.ml.util.Identifiable

class CommonElementFilter(override val uid: String)
  extends Estimator[CommonElementFilterModel]
    with WordVectorPipelineStage {

  override val requiredColumns: Set[Param[String]] = Set(
    fileCol,
    wordCol
  )

  final val dropFreq = new Param[Double](this, "dropFreq", "The maximum allowed document frequency")
  setDefault(dropFreq, 0.2)

  def this() = this(Identifiable.randomUID("CommonElementFilter"))

  def setFileCol(value: String): CommonElementFilter = set(fileCol, value)

  def setWordCol(value: String): CommonElementFilter = set(wordCol, value)

  def setDropFreq(value: Double): CommonElementFilter = set(dropFreq, value)

  override def fit(dataset: Dataset[_]): CommonElementFilterModel = {
    val numDocs = dataset.select($(fileCol)).distinct.count

    val wordsToKeep = dataset.groupBy($(wordCol))
      .count
      .filter {
        row => row.getAs[Long]("count") < $(dropFreq) * numDocs
      }
      .select($(wordCol))

    new CommonElementFilterModel(wordsToKeep)
      .setFileCol($(fileCol))
      .setWordCol($(wordCol))
  }

  override def copy(extra: ParamMap): Estimator[CommonElementFilterModel] = defaultCopy(extra)
}
