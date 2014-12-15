package edu.illinois.ncsa.daffodil.section23.dfdl_expressions

/* Copyright (c) 2012-2013 Tresys Technology, LLC. All rights reserved.
 *
 * Developed by: Tresys Technology, LLC
 *               http://www.tresys.com
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal with
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
 * of the Software, and to permit persons to whom the Software is furnished to do
 * so, subject to the following conditions:
 * 
 *  1. Redistributions of source code must retain the above copyright notice,
 *     this list of conditions and the following disclaimers.
 * 
 *  2. Redistributions in binary form must reproduce the above copyright
 *     notice, this list of conditions and the following disclaimers in the
 *     documentation and/or other materials provided with the distribution.
 * 
 *  3. Neither the names of Tresys Technology, nor the names of its contributors
 *     may be used to endorse or promote products derived from this Software
 *     without specific prior written permission.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * CONTRIBUTORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS WITH THE
 * SOFTWARE.
 */

import junit.framework.Assert._
import org.junit.Test
import scala.xml._
import edu.illinois.ncsa.daffodil.xml.XMLUtils
import edu.illinois.ncsa.daffodil.xml.XMLUtils._
import edu.illinois.ncsa.daffodil.compiler.Compiler
import edu.illinois.ncsa.daffodil.util._
import edu.illinois.ncsa.daffodil.tdml.DFDLTestSuite
import java.io.File
import edu.illinois.ncsa.daffodil.debugger.Debugger

class TestDFDLExpressionsDebug {
  val testDir = "/edu/illinois/ncsa/daffodil/section23/dfdl_expressions/"
  val tdml = testDir + "expressions.tdml"
  lazy val runner = new DFDLTestSuite(Misc.getRequiredResource(tdml))
  
  //DFDL-1188
  @Test def test_hiddenDataExpression() { runner.runOneTest("hiddenDataExpression") }
  @Test def test_hiddenDataExpression2() { runner.runOneTest("hiddenDataExpression2") }

  //DFDL-1148
  @Test def test_asterisk_01() { runner.runOneTest("asterisk_01") }
  @Test def test_asterisk_02() { runner.runOneTest("asterisk_02") }

  //DFDL-1146
  @Test def test_attribute_axis_01() { runner.runOneTest("attribute_axis_01") }
  @Test def test_attribute_axis_02() { runner.runOneTest("attribute_axis_02") }
  @Test def test_attribute_axis_03() { runner.runOneTest("attribute_axis_03") }

  //DFDL-1035 - tests need better diagnostic
  @Test def test_dfdlCheckConstraints() { runner.runOneTest("dfdlCheckConstraints") }
  @Test def test_dfdlCheckConstraints2() { runner.runOneTest("dfdlCheckConstraints2") }

  @Test def test_regexCompatFail() { runner.runOneTest("regexCompatFail") }

  // lengthUnits bytes with variable-width charater set and specified lengthKind
  @Test def test_lke3_rel() { runner.runOneTest("lke3_rel") } // uses lengthUnits bytes with utf-8 

  // DFDL-1043
  @Test def test_checkConstraintsComplexTypeFails() { runner.runOneTest("checkConstraintsComplexTypeFails") }

  // DFDL-1044
  @Test def test_expression_type_error2() { runner.runOneTest("expression_type_error2") }

  //DFDL-1164
  @Test def test_predicate_02() { runner.runOneTest("predicate_02") }
  @Test def test_predicate_03() { runner.runOneTest("predicate_03") }

  //DFDL-1059
  @Test def test_parent_axis_01() { runner.runOneTest("parent_axis_01") }
  @Test def test_child_axis_01() { runner.runOneTest("child_axis_01") }
  @Test def test_self_axis_01() { runner.runOneTest("self_axis_01") }
  @Test def test_multiple_axis_01() { runner.runOneTest("multiple_axis_01") }

  //DFDL-1191
  @Test def test_ancestor_axis_01() { runner.runOneTest("ancestor_axis_01") }
  @Test def test_ancestor_or_self_axis_01() { runner.runOneTest("ancestor_or_self_axis_01") }
  @Test def test_attribute_axis_04() { runner.runOneTest("attribute_axis_04") }
  @Test def test_descendant_axis_01() { runner.runOneTest("descendant_axis_01") }
  @Test def test_descendant_or_self_axis_01() { runner.runOneTest("descendant_or_self_axis_01") }
  @Test def test_following_axis_01() { runner.runOneTest("following_axis_01") }
  @Test def test_following_sibling_axis_01() { runner.runOneTest("following_sibling_axis_01") }
  @Test def test_namespace_axis_01() { runner.runOneTest("namespace_axis_01") }
  @Test def test_preceding_axis_01() { runner.runOneTest("preceding_axis_01") }
  @Test def test_preceding_sibling_axis_01() { runner.runOneTest("preceding_sibling_axis_01") }

  //DFDL-711
  @Test def test_short_parent_axis_01() { runner.runOneTest("short_parent_axis_01") }

  val testDir2 = "/edu/illinois/ncsa/daffodil/section23/dfdl_functions/"
  val aa = testDir2 + "Functions.tdml"
  val aa_utf8 = testDir2 + "Functions_UTF8.tdml"
  lazy val runner2 = new DFDLTestSuite(Misc.getRequiredResource(aa))
  lazy val runner2_utf8 = new DFDLTestSuite(Misc.getRequiredResource(aa))

  //DFDL-1076
  @Test def test_not_04() { runner2.runOneTest("not_04") }

  //DFDL-1115
  @Test def test_xsDateTime_constructor_03() { runner2.runOneTest("xsDateTime_constructor_03") }

  //DFDL-1116
  @Test def test_count_03() { runner2.runOneTest("count_03") }

  //DFDL-1160
  @Test def test_count_04() { runner2.runOneTest("count_04") }

  //DFDL-1159 (unordered sequences)
  @Test def test_count_05() { runner2.runOneTest("count_05") }
  @Test def test_count_06() { runner2.runOneTest("count_06") }
  @Test def test_count_08() { runner2.runOneTest("count_08") }

  //DFDL-1118
  @Test def test_more_count_0() { runner2.runOneTest("more_count_0") }
  @Test def test_more_count_1() { runner2.runOneTest("more_count_1") }
  @Test def test_more_count_1b() { runner2.runOneTest("more_count_1b") }
  @Test def test_more_count_1b_2() { runner2.runOneTest("more_count_1b_2") }
  @Test def test_more_count_2() { runner2.runOneTest("more_count_2") }

  //DFDL-1075
  @Test def test_exists_01() { runner2.runOneTest("exists_01") }
  @Test def test_exists_03() { runner2.runOneTest("exists_03") }
  @Test def test_exists_04() { runner2.runOneTest("exists_04") }
  @Test def test_exists_06() { runner2.runOneTest("exists_06") }
  @Test def test_exists_07() { runner2.runOneTest("exists_07") }
  @Test def test_not_05() { runner2.runOneTest("not_05") }
  @Test def test_not_07() { runner2.runOneTest("not_07") }

  //DFDL-1120
  @Test def test_exists_10() { runner2.runOneTest("exists_10") }

  //DFDL-1124
  @Test def test_date_constructor_01() { runner2.runOneTest("date_constructor_01") }

  //DFDL-1169
  //@Test def test_lowercase_04() { runner2_utf8.runOneTest("lowercase_04") }
  //@Test def test_uppercase_04() { runner2_utf8.runOneTest("uppercase_04") }
  //@Test def test_uppercase_05() { runner2_utf8.runOneTest("uppercase_05") }
  //DFDL-1078 - fails on build server 
  //@Test def test_lowercase_05() { runner2_utf8.runOneTest("lowercase_05") }

  //DFDL-1080
  @Test def test_empty_02() { runner2.runOneTest("empty_02") }
  @Test def test_exists_02() { runner2.runOneTest("exists_02") }
  //DFDL-1079
  @Test def test_empty_05() { runner2.runOneTest("empty_05") }
  @Test def test_exists_05() { runner2.runOneTest("exists_05") }
 
  //DFDL-1085
  @Test def test_exactly_one_01() { runner2.runOneTest("exactly_one_01") }
  @Test def test_exactly_one_04() { runner2.runOneTest("exactly_one_04") }
  @Test def test_exactly_one_05() { runner2.runOneTest("exactly_one_05") }
  @Test def test_exactly_one_06() { runner2.runOneTest("exactly_one_06") }
  //DFDL-1087
  @Test def test_exactly_one_02() { runner2.runOneTest("exactly_one_02") }
  @Test def test_exactly_one_03() { runner2.runOneTest("exactly_one_03") }

  //DFDL-1091
  @Test def test_count_05b() { runner2.runOneTest("count_05b") }

  // Fails due to invariant failure slotIndexInParent
  @Test def test_local_name_07() { runner2.runOneTest("local_name_07") }

  //DFDL-1097
  @Test def test_local_name_06() { runner2.runOneTest("local_name_06") }

  //DFDL-1101
  @Test def test_namespace_uri_01() { runner2.runOneTest("namespace_uri_01") }
  @Test def test_namespace_uri_02() { runner2.runOneTest("namespace_uri_02") }
  //DFDL-1114
  @Test def test_namespace_uri_03() { runner2.runOneTest("namespace_uri_03") }
  @Test def test_namespace_uri_04() { runner2.runOneTest("namespace_uri_04") }
  @Test def test_namespace_uri_05() { runner2.runOneTest("namespace_uri_05") }
  @Test def test_namespace_uri_06() { runner2.runOneTest("namespace_uri_06") }

  // DFDL-581
  @Test def test_valueLength_0() { runner2.runOneTest("valueLength_0") }
  @Test def test_valueLength_1() { runner2.runOneTest("valueLength_1") }

  // DFDL-578
  @Test def test_contentLength_0() { runner2.runOneTest("contentLength_0") }
  @Test def test_contentLength_1() { runner2.runOneTest("contentLength_1") }

  // DFDL-1111: Inadequate diagnostic message from DPath Expression parser
  @Test def test_fn_text_01() { runner2.runOneTest("fn_text_01") }
  @Test def test_fn_text_02() { runner2.runOneTest("fn_text_02") }

  val testDir4 = "/edu/illinois/ncsa/daffodil/section23/runtime_properties/"
  val rp = testDir4 + "runtime-properties.tdml"
  lazy val runner4 = new DFDLTestSuite(Misc.getRequiredResource(rp))

  @Test def test_element_long_form_whitespace() { runner.runOneTest("element_long_form_whitespace") }
}
