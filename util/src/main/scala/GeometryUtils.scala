// Copyright 2013 Foursquare Labs Inc. All Rights Reserved.

package com.foursquare.twofishes.util

import com.google.common.geometry.{S2CellId, S2LatLng, S2LatLngRect, S2Polygon, S2PolygonBuilder, S2RegionCoverer}
import com.vividsolutions.jts.geom.{Geometry, Polygon, Point}
import java.io.{ByteArrayInputStream, ByteArrayOutputStream, DataInputStream, DataOutputStream}
import scalaj.collection.Implicits._

trait RevGeoConstants {
  val minS2Level = 8
  val maxS2Level = 12
  val maxCells = 10000
  val defaultLevelMod = 2
}

object RevGeoConstants extends RevGeoConstants

object GeometryUtils extends RevGeoConstants {
  def getBytes(l: S2CellId): Array[Byte] = getBytes(l.id())

  def getBytes(l: Long): Array[Byte] = ByteUtils.longToBytes(l)
  def getBytes(i: Int): Array[Byte] = ByteUtils.intToBytes(i)
  def getLongFromBytes(bytes: Array[Byte]): Long = ByteUtils.getLongFromBytes(bytes)

  def getS2CellIdForLevel(lat: Double, long: Double, s2Level: Int): S2CellId = {
    val ll = S2LatLng.fromDegrees(lat, long)
    S2CellId.fromLatLng(ll).parent(s2Level)
  }

  /**
   * Returns an `Iterable` of cells that cover a rectangle.
   */
  def rectCover(topRight: (Double,Double), bottomLeft: (Double,Double),
                minLevel: Int,
                maxLevel: Int,
                levelMod: Option[Int]):
      Seq[com.google.common.geometry.S2CellId] = {
    val topRightPoint = S2LatLng.fromDegrees(topRight._1, topRight._2)
    val bottomLeftPoint = S2LatLng.fromDegrees(bottomLeft._1, bottomLeft._2)

    val rect = S2LatLngRect.fromPointPair(topRightPoint, bottomLeftPoint)
    rectCover(rect, minLevel, maxLevel, levelMod)
  }

  def rectCover(rect: S2LatLngRect,
                minLevel: Int,
                maxLevel: Int,
                levelMod: Option[Int]):
      Seq[com.google.common.geometry.S2CellId] = {
    val coverer =  new S2RegionCoverer
    coverer.setMinLevel(minLevel)
    coverer.setMaxLevel(maxLevel)
    levelMod.foreach(m => coverer.setLevelMod(m))

    val coveringCells = new java.util.ArrayList[com.google.common.geometry.S2CellId]

    coverer.getCovering(rect, coveringCells)

    coveringCells.asScala
  }

  def s2BoundingBoxCovering(geomCollection: Geometry,
      minS2Level: Int, maxS2Level: Int) = {
    val envelope = geomCollection.getEnvelopeInternal()
    rectCover(
      topRight = (envelope.getMaxY(), envelope.getMaxX()),
      bottomLeft = (envelope.getMinY(), envelope.getMinX()),
      minLevel = minS2Level,
      maxLevel = maxS2Level,
      levelMod = None
    )
  }

  def s2Polygon(geomCollection: Geometry) = {
    if (geomCollection.getCoordinates().toList.exists(c => c.y > 90 || c.y < -90)) {
      throw new Exception("Geometry trying to cross a pole, can't handle: %s".format(geomCollection))
    }

    val polygons: List[S2Polygon] = (for {
     i <- 0.until(geomCollection.getNumGeometries()).toList
     val geom = geomCollection.getGeometryN(i)
     if (geom.isInstanceOf[Polygon])
    } yield {
      val poly = geom.asInstanceOf[Polygon]
      val ring = poly.getExteriorRing()
      val coords = ring.getCoordinates()
      val builder = new S2PolygonBuilder()
      (coords ++ List(coords(0))).sliding(2).foreach(pair => {
        val p1 = pair(0)
        val p2 = pair(1)
        builder.addEdge(
          S2LatLng.fromDegrees(p1.y, p1.x).toPoint,
          S2LatLng.fromDegrees(p2.y, p2.x).toPoint
        )
      })
      builder.assemblePolygon()
    })
    val builder = new S2PolygonBuilder()
    polygons.foreach(p => builder.addPolygon(p))
    builder.assemblePolygon()
  }

  def s2PolygonCovering(geomCollection: Geometry,
    minS2Level: Int = minS2Level,
    maxS2Level: Int = maxS2Level,
    maxCellsHintWhichMightBeIgnored: Option[Int] = Some(maxCells),
    levelMod: Option[Int] = Some(defaultLevelMod)
  ): Seq[S2CellId] = {
    if (geomCollection.isInstanceOf[Point]) {
      val point = geomCollection.asInstanceOf[Point]
      val lat = point.getY
      val lng = point.getX
      minS2Level.to(maxS2Level, levelMod.getOrElse(1)).map(level => {
        GeometryUtils.getS2CellIdForLevel(lat, lng, level)
      })
    } else {
      val s2poly = s2Polygon(geomCollection)
      val coverer =  new S2RegionCoverer
      coverer.setMinLevel(minS2Level)
      coverer.setMaxLevel(maxS2Level)
      maxCellsHintWhichMightBeIgnored.foreach(coverer.setMaxCells)
      levelMod.foreach(m => coverer.setLevelMod(m))
      val coveringCells = new java.util.ArrayList[com.google.common.geometry.S2CellId]
      coverer.getCovering(s2poly, coveringCells)
      coveringCells.asScala.toSeq
    }
  }

  def coverAtAllLevels(geomCollection: Geometry,
      minS2Level: Int,
      maxS2Level: Int,
      levelMod: Option[Int] = None
    ): Seq[S2CellId] = {
    val initialCovering = s2PolygonCovering(geomCollection,
      minS2Level = maxS2Level,
      maxS2Level = maxS2Level,
      levelMod = levelMod
    )

    val allCells = Set.newBuilder[S2CellId]
    // The final set of cells will be at most all the parents of the cells in
    // the initial covering. Wolfram alpha says that'll top out at 4/3 the
    // initial size:
    // http://www.wolframalpha.com/input/?i=sum+from+0+to+30+of+1%2F%284%5En%29.
    // In practice, it'll be less than that since we don't ask for all the
    // levels. But this is a reasonable upper bound.
    allCells.sizeHint((initialCovering.size*4)/3)

    initialCovering.foreach(cellid => {
      val level = cellid.level()
      allCells += cellid

      if (level > minS2Level) {
        level.to(minS2Level, -levelMod.getOrElse(1)).drop(1).foreach(l => {
          val p = cellid.parent(l)
          allCells += p
        })
      }
    })

    allCells.result.toSeq
  }

  val EarthRadiusInMeters: Int = 6378100 // Approximately a little less than the Earth's equatorial radius

  def getDistanceAccurate(geolat1: Double, geolong1: Double, geolat2: Double, geolong2: Double): Double = {
    val theta = geolong1 - geolong2
    val dist = math.sin(math.toRadians(geolat1)) * math.sin(math.toRadians(geolat2)) +
               math.cos(math.toRadians(geolat1)) * math.cos(math.toRadians(geolat2)) * math.cos(math.toRadians(theta))
    // Clamp dist to [-1, 1] since that's the defined range of outputs of cosine.
    (EarthRadiusInMeters * math.acos(math.min(math.max(dist, -1.0), 1.0)))
  }

  def pointToShapeDistance(point: Point, shape: Geometry): Double = {
    // 1. compute linear distance
    // 2. buffer point by distance plus epsilon
    // 3. intersect buffered point with shape
    // 4. get centroid for intersection
    // 5. compute real distance from point to centroid
    val dist = point.distance(shape)
    if (dist == 0.0) {
      dist
    } else {
      var epsilon = 0.0000001
      var bufferedPoint = point.buffer(dist + epsilon)
      while (!bufferedPoint.intersects(shape)) {
        epsilon = epsilon * 10
        bufferedPoint = point.buffer(dist + epsilon)
      }

      val centroid = bufferedPoint.intersection(shape).getCentroid()
      getDistanceAccurate(point.getY(), point.getX(), centroid.getY(), centroid.getX())
    }
  }
}
