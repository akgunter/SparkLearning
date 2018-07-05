package net.ddns.akgunter.spark_learning.data_processing

import org.apache.spark.ml.PipelineStage
import org.apache.spark.ml.linalg.SQLDataTypes.VectorType
import org.apache.spark.ml.param.{Param, ParamPair}
import org.apache.spark.sql.sources.v2.reader.SupportsPushDownRequiredColumns
import org.apache.spark.sql.types._

trait WordVectorPipelineStage extends PipelineStage with WordVectorParams {

  protected val requiredColumns: Set[Param[String]]

  override def transformSchema(schema: StructType): StructType = {
    val requiredColumnStrings = requiredColumns.map(param => $(param))
    val inputColumns = schema.fieldNames.toSet
    require(
      requiredColumnStrings.forall(inputColumns),
      s"Dataset is missing required column(s): ${requiredColumnStrings.diff(inputColumns).mkString(", ")}"
    )

    val requiredColumnTypes = WordVectorParams.COLUMN_TYPES.filter {
      case (colStr, _) => requiredColumnStrings(colStr)
    }
    val inputColumnTypes = schema.fieldNames.map {
      colStr => colStr -> schema.fields(schema.fieldIndex(colStr)).dataType
    }.toMap
    val failedColTypes = requiredColumnTypes.map {
      case (colStr, reqColType) => colStr -> (reqColType, inputColumnTypes(colStr))
    }.filterNot {
      case (_, (reqColType, realColType)) => reqColType == realColType
    }
    require(
      failedColTypes.isEmpty,
      s"Dataset has incorrect column type(s):\n${failedColTypes.map {
        case (colStr, (reqColType, realColType)) =>
          s"$colStr expected: $reqColType got: $realColType"
      }.mkString(", ")}"
    )

    val outputColumnTypes = inputColumnTypes ++ requiredColumnTypes
    val outSchema = WordVectorPipelineStage.buildSchema(outputColumnTypes)

    if (schema.fieldNames.contains($(labelCol)) && !outSchema.fieldNames.contains($(labelCol)))
      outSchema.add($(labelCol), IntegerType)
    else
      outSchema
  }
}

object WordVectorPipelineStage {
  protected def buildSchema(columnTypes: Map[String, DataType]): StructType = {
    println(columnTypes.mkString(", "))
    buildSchema(columnTypes.iterator, new StructType())
  }

  protected def buildSchema(colTypeIter: Iterator[(String, DataType)], schema: StructType): StructType = {
    if (!colTypeIter.hasNext)
      schema
    else {
      val (nextCol, nextType) = colTypeIter.next
      buildSchema(colTypeIter, schema.add(nextCol, nextType))
    }
  }
}