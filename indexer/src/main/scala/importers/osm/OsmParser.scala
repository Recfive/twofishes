import crosby.binary._
import crosby.binary.file.BlockInputStream
import scala.collection.JavaConversions._
import java.io._

class OsmParser extends BinaryParser {
  def doParse(filename: String) {
    val fis = new FileInputStream(filename)
    val bis = new BlockInputStream(fis, this)
    bis.process()
  }

  override protected def parseRelations(rels: java.util.List[Osmformat.Relation]) {
    println(rels)
  }

  /** Parse a DenseNode protocol buffer and send the resulting nodes to a sink.  */
  override protected def parseDense(nodes: Osmformat.DenseNodes) {
    println(nodes)
  }
  /** Parse a list of Node protocol buffers and send the resulting nodes to a sink.  */
  override protected def parseNodes(nodes: java.util.List[Osmformat.Node]) {
    nodes.foreach(node => {
      println
      0.to(node.getKeysCount()).foreach(kIndex => {
        val key = getStringById(node.getKeys(kIndex));
        val value = getStringById(node.getVals(kIndex));
        println("%s %s".format(key, value))
      })
    })
  }
  /** Parse a list of Way protocol buffers and send the resulting ways to a sink.  */
  override protected def parseWays(ways: java.util.List[Osmformat.Way]) {
    println(ways)
  }
  /** Parse a header message. */
  override protected def parse(header: Osmformat.HeaderBlock) {
    println(header)
  }

  protected def complete() {}
}