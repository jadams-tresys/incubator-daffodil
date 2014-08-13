package edu.illinois.ncsa.daffodil.processors

/* Copyright (c) 2012-2014 Tresys Technology, LLC. All rights reserved.
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

import edu.illinois.ncsa.daffodil.grammar.Gram
import edu.illinois.ncsa.daffodil.grammar.NamedGram
import edu.illinois.ncsa.daffodil.schema.annotation.props.gen.TestKind
import edu.illinois.ncsa.daffodil.exceptions.Assert
import edu.illinois.ncsa.daffodil.dsom.ElementBase
import edu.illinois.ncsa.daffodil.dsom.DiagnosticUtils._
import edu.illinois.ncsa.daffodil.dsom.Term
import edu.illinois.ncsa.daffodil.dsom.Sequence
import edu.illinois.ncsa.daffodil.util.LogLevel
import edu.illinois.ncsa.daffodil.debugger.Debugger
import scala.collection.JavaConversions._
import scala.collection.mutable.Buffer
import scala.collection.mutable.ListBuffer
import edu.illinois.ncsa.daffodil.xml.XMLUtils
import edu.illinois.ncsa.daffodil.grammar.UnaryGram
import scala.xml.Elem
import edu.illinois.ncsa.daffodil.xml.NS

object UnorderedSequence {
  def apply(context: Term, eGram: Gram) = {
    // mandatory little optimization here. If there are no statements (most common case), then let's 
    // shortcut and just use the guts parser.

    Assert.usageErrorUnless(context.isInstanceOf[Sequence], "The context passed to UnorderedSequence must be a Sequence.")

    val ctxt = context.asInstanceOf[Sequence]

    new UnorderedSequence(ctxt, eGram)
  }
}

class UnorderedSequence private (context: Sequence, eGram: Gram) // private to force use of the object as factory
  extends UnaryGram(context, eGram) {

  // Forced as part of required evaluations in Sequence
  //context.checkIfValidUnorderedSequence

  val uoSeqParser = eGram.parser
  val sortOrder = {
    val members = context.groupMembers.map(t => {
      t match {
        case s: Sequence => s.groupMembers
        case _ => Seq(t)
      }
    }).flatten

    members.map(t => {
      val erd = t.runtimeData.asInstanceOf[ElementRuntimeData]
      val name = erd.name
      val ns = erd.targetNamespace.toJDOM
      (name, ns)
    })
  }

  val scalarMembers =
    context.groupMembers.filter(t => t.isInstanceOf[ElementBase]).filter {
      case eb: ElementBase => eb.isScalar
    }.map {
      case eb: ElementBase => {
        val erd = eb.runtimeData.asInstanceOf[ElementRuntimeData]
        (erd.name, erd.path, erd.targetNamespace.toJDOM)
      }
    }

  def parser: Parser = new UnorderedSequenceParser(context.modelGroupRuntimeData, sortOrder, scalarMembers, uoSeqParser)


  def unparser: Unparser = new Unparser(context) {
    def unparse(start: UState): UState = {
      val eUnParser = eGram.unparser
      val postEState = eUnParser.unparse(start)
      postEState
    }
  }

}