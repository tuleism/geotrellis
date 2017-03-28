package geotrellis.pointcloud.spark.triangulation

import geotrellis.raster._
import geotrellis.raster.triangulation.DelaunayRasterizer
import geotrellis.spark.buffer.Direction
import geotrellis.spark.buffer.Direction._
import geotrellis.vector._
import geotrellis.vector.io.wkt.WKT
import geotrellis.vector.triangulation._

import com.vividsolutions.jts.geom.Coordinate

object StitchedDelaunay {

  def directionToVertexOffset(d: Direction) = {
    val increment = Int.MaxValue / 9
    d match {
      case TopLeft => 0
      case Top => increment
      case TopRight => 2 * increment
      case Left => 3 * increment
      case Center => 4 * increment
      case Right => 5 * increment
      case BottomLeft => 6 * increment
      case Bottom => 7 * increment
      case BottomRight => 8 * increment
    }
  }

  def indexToVertex(neighbors: Map[Direction, (BoundaryDelaunay, Extent)]): Int => Coordinate =
    { i =>
      val increment = Int.MaxValue / 9
      val group = i / increment
      val index = i % increment
      val dir = group match {
        case 0 => TopLeft
        case 1 => Top
        case 2 => TopRight
        case 3 => Left
        case 4 => Center
        case 5 => Right
        case 6 => BottomLeft
        case 7 => Bottom
        case 8 => BottomRight
      }
      neighbors(dir)._1.pointSet.getCoordinate(index)
    }

  def indexToVertex(neighbors: Map[Direction, (DelaunayTriangulation, Extent)])(implicit dummy: DummyImplicit): Int => Coordinate =
    { i =>
      val increment = Int.MaxValue / 9
      val group = i / increment
      val index = i % increment
      val dir = group match {
        case 0 => TopLeft
        case 1 => Top
        case 2 => TopRight
        case 3 => Left
        case 4 => Center
        case 5 => Right
        case 6 => BottomLeft
        case 7 => Bottom
        case 8 => BottomRight
      }
      neighbors(dir)._1.pointSet.getCoordinate(index)
    }

  /**
    * Given a set of BoundaryDelaunay objects and their non-overlapping boundary
    * extents, each pair associated with a cardinal direction, this function
    * creates a merged representation
    */
  def apply(neighbors: Map[Direction, (BoundaryDelaunay, Extent)], debug: Boolean = false): StitchedDelaunay = {
    val vertCount = neighbors.map{ case (_, (bdt, _)) => bdt.pointSet.length }.reduce(_ + _)
    val allEdges = new HalfEdgeTable(2 * (3 * vertCount - 6))
    val pointMap = indexToVertex(neighbors)
    val allPoints = DelaunayPointSet(pointMap, vertCount)
    val boundaries = neighbors.map{ case (dir, (bdt, _)) => {
      val offset = directionToVertexOffset(dir)
      val reindex = {x: Int => x + offset}
      val edgeoffset = allEdges.appendTable(bdt.halfEdgeTable, reindex)
      (dir, (bdt.boundary + edgeoffset, bdt.isLinear))
    }}


    val dirs = Seq(Seq(TopLeft, Top, TopRight), Seq(Left, Center, Right), Seq(BottomLeft, Bottom, BottomRight))
    val overlayTris = new TriangleMap(allEdges)
    val stitcher = new DelaunayStitcher(allPoints, allEdges)

    dirs
      .map{row => row.flatMap{ dir => boundaries.get(dir) }}
      .filter{ row => !row.isEmpty }
      .map{row => row.reduce{ (l, r) => {
        val (left, isLeftLinear) = l
        val (right, isRightLinear) = r
        val result = stitcher.merge(left, isLeftLinear, right, isRightLinear, overlayTris, debug)
        allEdges.navigate(result._1, pointMap, Map(
          't' -> ("export triangles to triangles.wkt", { e0 =>
            val mp = MultiPolygon(overlayTris.triangleVertices.map{ case (i,j,k) => {
              Polygon(pointMap(i), pointMap(j), pointMap(k), pointMap(i))
            }})
            new java.io.PrintWriter("triangles.wkt") { write(WKT.write(mp)); close }
            e0
          }),
          'x' -> ("export current loop to poly.wkt", { e0 =>
            import allEdges._
            var e = e0
            val pts = collection.mutable.ListBuffer[Coordinate](pointMap(getSrc(e)))
            do {
              pts += pointMap(getDest(e))
              e = getNext(e)
            } while (e != e0)
            val mp = Polygon(pts.map(Point.jtsCoord2Point(_)))
            new java.io.PrintWriter("poly.wkt") { write(WKT.write(mp)); close }
            e0
          })
        ))
        result
      }}}
      .reduce{ (l, r) => {
        val (left, isLeftLinear) = l
        val (right, isRightLinear) = r
        stitcher.merge(left, isLeftLinear, right, isRightLinear, overlayTris, debug)
      }}

    new StitchedDelaunay(pointMap, allEdges, allPoints, overlayTris)
  }

  def apply(neighbors: Map[Direction, (DelaunayTriangulation, Extent)], debug: Boolean)(implicit dummy: DummyImplicit): StitchedDelaunay = {
    val vertCount = neighbors.map{ case (_, (bdt, _)) => bdt.pointSet.length }.reduce(_ + _)
    val allEdges = new HalfEdgeTable(2 * (3 * vertCount - 6))
    val pointMap = indexToVertex(neighbors)
    val allPoints = DelaunayPointSet(pointMap, vertCount)

    val boundaries = neighbors.map{ case (dir, (bdt, _)) => {
      val offset = directionToVertexOffset(dir)
      val reindex = {x: Int => x + offset}
      val edgeoffset = allEdges.appendTable(bdt.halfEdgeTable, reindex)
      (dir, (bdt.boundary + edgeoffset, bdt.isLinear))
    }}

    val dirs = Seq(Seq(TopLeft, Top, TopRight), Seq(Left, Center, Right), Seq(BottomLeft, Bottom, BottomRight))
    val overlayTris = new TriangleMap(allEdges)
    val stitcher = new DelaunayStitcher(allPoints, allEdges)

    dirs
      .map{row => row.flatMap{ dir => boundaries.get(dir) }}
      .filter{ row => !row.isEmpty }
      .map{row => row.reduce{ (l, r) => {
        val (left, isLeftLinear) = l
        val (right, isRightLinear) = r
        val result = stitcher.merge(left, isLeftLinear, right, isRightLinear, overlayTris, debug)
        //allEdges.navigate(result._1, pointMap, Map.empty)
        result
      }}}
      .reduce{ (l, r) => {
        val (left, isLeftLinear) = l
        val (right, isRightLinear) = r
        stitcher.merge(left, isLeftLinear, right, isRightLinear, overlayTris, debug)
      }}

    new StitchedDelaunay(pointMap, allEdges, allPoints, overlayTris)
  }
}

case class StitchedDelaunay(
  indexToCoord: Int => Coordinate,
  private val edges: HalfEdgeTable,
  private val pointSet: DelaunayPointSet,
  private val fillTriangles: TriangleMap
) {
  def triangles(): Seq[(Int, Int, Int)] = fillTriangles.getTriangles.keys.toSeq

  def writeWKT(wktFile: String) = {
    val mp = MultiPolygon(triangles.map{ case (i,j,k) => Polygon(indexToCoord(i), indexToCoord(j), indexToCoord(k), indexToCoord(i)) })
    val wktString = WKT.write(mp)
    new java.io.PrintWriter(wktFile) { write(wktString); close }
  }


  def rasterize(re: RasterExtent, cellType: CellType = DoubleConstantNoDataCellType)(center: DelaunayTriangulation) = {
    val tile = ArrayTile.empty(cellType, re.cols, re.rows)
    DelaunayRasterizer.rasterizeDelaunayTriangulation(center, re, tile)
    DelaunayRasterizer.rasterize(
      tile,
      re,
      fillTriangles,
      edges,
      pointSet
    )
    tile
  }
}
