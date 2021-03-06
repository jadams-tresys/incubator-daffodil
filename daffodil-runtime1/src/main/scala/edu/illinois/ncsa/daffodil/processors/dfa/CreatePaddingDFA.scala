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

package edu.illinois.ncsa.daffodil.processors.dfa

import scala.collection.mutable.ArrayBuffer
import edu.illinois.ncsa.daffodil.processors.Delimiter
import edu.illinois.ncsa.daffodil.processors.RuntimeData
import edu.illinois.ncsa.daffodil.processors.parsers.DelimiterTextType

object CreatePaddingDFA {

  /**
   * Constructs a DFADelimiter object that specifically
   * looks for padChar.
   */
  def apply(padChar: Char, rd: RuntimeData): DFADelimiter = {
    // TODO: In the future we will need to change this because the padChar isn't necessarily a char.
    // One can use it to specify a numeric byte to be used to pad as well.

    val allStates: ArrayBuffer[State] = ArrayBuffer.empty

    val startState = new StartStatePadding(allStates, padChar)

    allStates.insert(0, startState)

    new DFADelimiterImpl(DelimiterTextType.Other, allStates.toArray, padChar.toString(), rd.schemaFileLocation)
  }

  /**
   * Constructs a DFADelimiter object that specifically
   * looks for padChar.
   */
  def apply(padChar: Char, outputNewLine: String, rd: RuntimeData): DFADelimiter = {
    // TODO: In the future we will need to change this because the padChar isn't necessarily a char.
    // One can use it to specify a numeric byte to be used to pad as well.

    val allStates: ArrayBuffer[State] = ArrayBuffer.empty

    val startState = new StartStatePadding(allStates, padChar)

    allStates.insert(0, startState)

    val d = new Delimiter()
    d.compileDelimiter(padChar.toString, false)

    val unparseValue = d.delimBuf.map { _.unparseValue("") }.mkString

    new DFADelimiterImplUnparse(DelimiterTextType.Other, allStates.toArray, padChar.toString(), unparseValue, rd.schemaFileLocation)
  }
}
