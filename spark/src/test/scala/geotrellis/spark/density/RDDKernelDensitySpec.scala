package geotrellis.spark.density

import scala.util.Random
import scala.math.{max,min}

import org.apache.spark.rdd.RDD

import geotrellis.proj4._
import geotrellis.raster._
import geotrellis.raster.density._
import geotrellis.raster.mapalgebra.focal._
import geotrellis.raster.mapalgebra.local._
import geotrellis.raster.testkit._
import geotrellis.spark._
import geotrellis.spark.stitch._
import geotrellis.spark.tiling._
import geotrellis.vector._

import geotrellis.raster.render._

import org.scalatest._

class KernelDensityRDDSpec extends FunSpec
    with Matchers
    with TestEnvironment
    with RasterMatchers {

  describe("Kernel Density Operation on RDD of features") {
    it("should produce the same result as non-RDD process") {
      // Generate points (random?)
      def randomPointFeature(extent: Extent) : PointFeature[Double] = {
        def randInRange (low : Double, high : Double) : Double = {
          val x = Random.nextDouble
          low * (1-x) + high * x
        }
        new PointFeature(Point(randInRange(extent.xmin,extent.xmax),
                               randInRange(extent.ymin,extent.ymax)), 
                         Random.nextInt % 50 + 50)
      }

      val extent = Extent.fromString("-109,37,-102,41") // Colorado (is rect!)
      val pts = PointFeature(Point(-108,37.5),100.0) :: (for (i <- 1 to 1000) yield randomPointFeature(extent)).toList

      assert(pts.forall (extent.contains(_)))

      // stamp kernels for points to local raster
      
      val krnwdth = 9.0
      val kern = Kernel(Circle(krnwdth))
      val full = pts.kernelDensity(kern, RasterExtent(extent,700,400))

      // partition points into tiles

      val tl = TileLayout(7,4,100,100)
      val ld = LayoutDefinition(extent,tl)

      val ptrdd = sc.parallelize(pts, 10)

      val tileRDD = ptrdd.kernelDensity(kern, ld, LatLng)

      val tileList = 
        for { r <- 0 until ld.layoutRows
            ; c <- 0 until ld.layoutCols
            } yield {
              val k = SpatialKey(c,r)
              tileRDD.lookup(k) match {
                case Nil => (k, DoubleArrayTile.empty(tl.tileCols,tl.tileRows))
                case x => (k, x(0))
              }
            }
      val stitched = TileLayoutStitcher.stitch(tileList)

      // compare results
      assertEqual(stitched._1, full)
    }
  }

}
