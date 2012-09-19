package daffodil.section05.simple_types

import junit.framework.Assert._
import org.scalatest.junit.JUnitSuite
import org.junit.Test
import scala.xml._
import daffodil.xml.XMLUtils
import daffodil.xml.XMLUtils._
import daffodil.compiler.Compiler
import daffodil.util._
import daffodil.tdml.DFDLTestSuite
import java.io.File

class TestSimpleTypes2 extends JUnitSuite {
  val testDir = "/daffodil/section05/simple_types/"
  val aa = testDir + "SimpleTypes.tdml"
  lazy val runner = new DFDLTestSuite(Misc.getRequiredResource(aa))
  
  @Test def test_warning_exercise() { 
    val exc = intercept[Exception] {
    	runner.runOneTest("warning_exercise") }
    	assertTrue(exc.getMessage().contains("Did not find"))
  	}
//  @Test def test_Long3() {runner.runOneTest("Long3")}
//  @Test def test_Long4() {runner.runOneTest("Long4")}
  }
