// Copyright 2014 Foursquare Labs Inc. All Rights Reserved.
package com.foursquare.twofishes.scalding

import com.foursquare.common.thrift.ThriftConverter
import com.foursquare.twofishes._
import org.apache.hadoop.io.BytesWritable

object PolygonMatchingHelper {

  val thriftConverter = new ThriftConverter[PolygonMatchingKey]()

  def getS2LevelForWoeType(woeType: YahooWoeType): Int = {
    woeType match {
      case YahooWoeType.COUNTRY => 4
      case YahooWoeType.ADMIN1 => 6
      case YahooWoeType.ADMIN2 => 6
      case YahooWoeType.ADMIN3 => 8
      case YahooWoeType.TOWN => 8
      case YahooWoeType.SUBURB => 10
      case _ => 10
    }
  }

  def getKeyAsBytesWritable(key: PolygonMatchingKey): BytesWritable = {
    new BytesWritable(thriftConverter.serialize(key))
  }
}