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

import edu.illinois.ncsa.daffodil.exceptions.Assert
import edu.illinois.ncsa.daffodil.processors.TextJustificationType
import edu.illinois.ncsa.daffodil.util.Maybe
import edu.illinois.ncsa.daffodil.util.Maybe.Nope
import edu.illinois.ncsa.daffodil.util.Maybe.One
import edu.illinois.ncsa.daffodil.processors.TermRuntimeData
import edu.illinois.ncsa.daffodil.processors.parsers.PaddingRuntimeMixin
import edu.illinois.ncsa.daffodil.io.DataInputStream
import edu.illinois.ncsa.daffodil.equality._
import edu.illinois.ncsa.daffodil.util.MaybeChar
import edu.illinois.ncsa.daffodil.processors.DelimiterIterator

abstract class TextDelimitedParserBase(
  override val justificationTrim: TextJustificationType.Type,
  override val parsingPadChar: MaybeChar,
  override val context: TermRuntimeData)
  extends Parser with PaddingRuntimeMixin {

  private lazy val padCharInfo = if (parsingPadChar.isDefined) parsingPadChar.toString else "NONE"
  lazy val info: String = "justification='" + justificationTrim + "', padChar='" + padCharInfo + "'"

  final def parse(input: DataInputStream, field: DFAField, delimIter: DelimiterIterator, isDelimRequired: Boolean): Maybe[ParseResult] = {
    Assert.invariant(field != null)

    val lmt = new LongestMatchTracker()

    val fieldReg: Registers = TLRegistersPool.getFromPool("TextDelimitedParserBase1")

    fieldReg.reset(input, delimIter) // Initialization

    var stillSearching: Boolean = true
    var beforeDelimiter: DataInputStream.MarkPos = DataInputStream.MarkPos.NoMarkPos
    while (stillSearching) {

      Assert.invariant(beforeDelimiter =#= DataInputStream.MarkPos.NoMarkPos)
      field.run(fieldReg)
      beforeDelimiter = input.markPos

      fieldReg.status match {
        case StateKind.EndOfData => stillSearching = false
        case StateKind.Failed => stillSearching = false
        case StateKind.Paused => {

          delimIter.reset()
          while (delimIter.hasNext()) {
            val d = delimIter.next()
            input.resetPos(beforeDelimiter)
            beforeDelimiter = input.markPos
            val delimReg: Registers = TLRegistersPool.getFromPool("TextDelimitedParserBase2")
            delimReg.reset(input, delimIter)
            d.run(delimReg)
            if (delimReg.status == StateKind.Succeeded) {
              lmt.successfulMatch(delimReg.matchStartPos, delimReg.delimString, d, delimIter.currentIndex)
            }
            TLRegistersPool.returnToPool(delimReg)
          }
          if (!lmt.longestMatches.isEmpty) { stillSearching = false }
          else {
            // resume field parse
            // TODO: Please explain here why this is the way one resumes the field dfa?
            // TODO: The above assignment to the actionNum is the only reason that actionNum can't just
            // be a local variable in the run-the-rules loop.
            //
            // I'd like this code better if the flow was different.
            //
            // Right now: When scanning to isolate a field, when we hit a character that could be the first
            // character of some delimiter, we return with status PAUSED, which means PAUSE to see if there is
            // a complete delimiter.
            // If so then we're done with the field. If not, however, then we go around the loop and resume ...
            // and the rub is we resume in the middle of the rules for the current state. This is subtle, and
            // error prone.
            //
            // A better flow would (a) encapsulate all this redundant code better (b) on encountering the
            // first character of a delimiter, transition to a state that represents that we found a possible
            // first character of a delimiter. Then that
            // state's rules would be guarded by finding the longest match delimiter. If found transition to a
            // state indicating a field has been isolated. If the delimiter is not found, then accumulate the character
            // as a constituent of the field, and transition to the start state.
            //
            input.resetPos(beforeDelimiter) // reposition input to where we were trying to find a delimiter (but did not)
            beforeDelimiter = DataInputStream.MarkPos.NoMarkPos
            fieldReg.actionNum = fieldReg.actionNum + 1 // but force it to goto next rule so it won't just retry what it just did.
            stillSearching = true
          }
        }
      }
    }
    Assert.invariant(beforeDelimiter != DataInputStream.MarkPos.NoMarkPos)
    input.resetPos(beforeDelimiter)
    val result = {
      if (lmt.longestMatches.isEmpty) {
        // there were no delimiter matches
        if (isDelimRequired) Nope
        else {
          val fieldValue: Maybe[String] = {
            val str = fieldReg.resultString.toString
            // TODO: Performance - avoid this copying of the string. We should be able to trim
            // on a CharSequence which is a base of both String and StringBuilder
            // Difficulty is the only common base to String and StringBuilder is CharSequence which is
            // pretty sparse.
            val fieldNoPadding = trimByJustification(str)
            One(fieldNoPadding)
          }
          One(new ParseResult(fieldValue, Nope, lmt.longestMatches))
        }
      } else {
        val fieldValue: Maybe[String] = {
          val str = fieldReg.resultString.toString // TODO: Performance see above.
          val fieldNoPadding = trimByJustification(str)
          One(fieldNoPadding)
        }
        val delim: Maybe[String] = {
          One(lmt.longestMatchedString)
        }

        One(new ParseResult(fieldValue, delim, lmt.longestMatches))
      }
    }

    TLRegistersPool.returnToPool(fieldReg)
    TLRegistersPool.pool.finalCheck

    result
  }

}

/**
 * Assumes that the delims DFAs were constructed with the Esc
 * and EscEsc in mind.
 */
class TextDelimitedParser(
  justArg: TextJustificationType.Type,
  padCharArg: MaybeChar,
  context: TermRuntimeData)
  extends TextDelimitedParserBase(justArg, padCharArg, context) {

  lazy val name: String = "TextDelimitedParser"

}

/**
 * Assumes that endBlock DFA was constructed with the
 * EscEsc in mind.
 */
class TextDelimitedParserWithEscapeBlock(
  justArg: TextJustificationType.Type,
  padCharArg: MaybeChar,
  context: TermRuntimeData)
  extends TextDelimitedParserBase(justArg, padCharArg, context) {

  lazy val name: String = "TextDelimitedParserWithEscapeBlock"

  val leftPadding: DFADelimiter = {
    justificationTrim match {
      case TextJustificationType.Center | TextJustificationType.Right if parsingPadChar.isDefined => CreatePaddingDFA(parsingPadChar.get, context)
      case _ => null
    }
  }

  val rightPadding: DFADelimiter = {
    justificationTrim match {
      case TextJustificationType.Center | TextJustificationType.Left if parsingPadChar.isDefined => CreatePaddingDFA(parsingPadChar.get, context)
      case _ => null
    }
  }

  protected def removeLeftPadding(input: DataInputStream, delimIter: DelimiterIterator): Unit = {
    justificationTrim match {
      case TextJustificationType.Center | TextJustificationType.Right if parsingPadChar.isDefined => {
        val leftPaddingRegister = TLRegistersPool.getFromPool("removeLeftPadding")
        leftPaddingRegister.reset(input, delimIter)
        leftPadding.run(leftPaddingRegister)
        TLRegistersPool.returnToPool(leftPaddingRegister)
      }
      case _ => // No left padding
    }
  }

  protected def removeRightPadding(input: DataInputStream, delimIter: DelimiterIterator): Unit = {
    justificationTrim match {
      case TextJustificationType.Center | TextJustificationType.Left if parsingPadChar.isDefined => {
        val rightPaddingRegister = TLRegistersPool.getFromPool("removeRightPadding")
        rightPaddingRegister.reset(input, delimIter)
        rightPadding.run(rightPaddingRegister)
        TLRegistersPool.returnToPool(rightPaddingRegister)
      }
      case _ => // No right padding
    }
  }

  protected def parseStartBlock(input: DataInputStream, startBlock: DFADelimiter, delimIter: DelimiterIterator): Boolean = {
    val startBlockRegister = TLRegistersPool.getFromPool("parseStartBlock")
    startBlockRegister.reset(input, delimIter)

    startBlock.run(startBlockRegister) // find the block start, fail otherwise
    val startStatus = startBlockRegister.status
    TLRegistersPool.returnToPool(startBlockRegister)
    startStatus match {
      case StateKind.Succeeded => true // continue
      case _ => false // Failed
    }
  }

  /**
   * Called to parse the rest of the field until we reach a block end, but
   * beyond that, after we reach a block-end out until we reach the delimiter.
   */
  protected def parseRemainder(input: DataInputStream,
    fieldEsc: DFAField,
    startBlock: DFADelimiter, endBlock: DFADelimiter,
    delimIter: DelimiterIterator, isDelimRequired: Boolean): Maybe[ParseResult] = {

    val lmt = new LongestMatchTracker()

    val fieldRegister = TLRegistersPool.getFromPool("parseRemainder")
    fieldRegister.reset(input, delimIter)

    var stillSearching: Boolean = true
    var foundBlockEnd: Boolean = false
    var beforeDelimiter: DataInputStream.MarkPos = DataInputStream.MarkPos.NoMarkPos
    while (stillSearching) {

      Assert.invariant(beforeDelimiter =#= DataInputStream.MarkPos.NoMarkPos)
      fieldEsc.run(fieldRegister)
      val dfaStatus = fieldRegister.status
      beforeDelimiter = input.markPos // at this point the input is one past the end of the field.
      fieldRegister.actionNum = 0

      dfaStatus match {
        case StateKind.EndOfData => stillSearching = false
        case StateKind.Failed => stillSearching = false
        case StateKind.Paused => {
          // Pick up where field left off, we are looking for
          // the blockEnd.
          val endBlockRegister = TLRegistersPool.getFromPool("parseRemainder2")
          endBlockRegister.reset(input, delimIter) // copy(fieldRegister) // TODO: This should just be a reset of the registers. No need to copy.

          endBlock.run(endBlockRegister)
          val endBlockStatus = endBlockRegister.status
          TLRegistersPool.returnToPool(endBlockRegister)

          endBlockStatus match {
            case StateKind.Succeeded => {
              // Found the unescaped block end, now we need to
              // find any padding.
              this.removeRightPadding(input, delimIter)
              beforeDelimiter = input.markPos

              delimIter.reset()
              while (delimIter.hasNext()) {
                // Finally, we can look for the delimiter.
                val d = delimIter.next() // Pick up where end of block/padding left off
                val delimRegister = TLRegistersPool.getFromPool("parseRemainder3")
                input.resetPos(beforeDelimiter)
                beforeDelimiter = input.markPos
                delimRegister.reset(input, delimIter)

                d.run(delimRegister)
                if (delimRegister.status == StateKind.Succeeded) {
                  lmt.successfulMatch(delimRegister.matchStartPos, delimRegister.delimString, d, delimIter.currentIndex)
                }
                TLRegistersPool.returnToPool(delimRegister)
              }
              foundBlockEnd = true
              stillSearching = false
            }
            case _ => {
              //
              // In this case we already found
              // a block start, (because we're in parseRemainder)
              // and we halted scanning because we found the start of a block end
              // However, it turns out not to be an entire block end.
              //
              // So we keep going. But we have to accumulate the
              // characters we were scrutinizing as the possible block end
              // into the field.
              //
              input.resetPos(beforeDelimiter)
              beforeDelimiter = DataInputStream.MarkPos.NoMarkPos
              fieldRegister.resetChars

              // resume field parse
              //
              // This resumes the field dfa by moving it onto the next rule
              // This assumes that the field dfa will resume properly using
              // it's existing state, so long as the input is repositioned properly.
              //
              fieldRegister.actionNum = fieldRegister.actionNum + 1 // goto next rule
            }
          }
        }
      }
    } // End While
    Assert.invariant(beforeDelimiter !=#= DataInputStream.MarkPos.NoMarkPos)
    input.resetPos(beforeDelimiter)
    val result = {
      if (lmt.longestMatches.isEmpty) {
        //
        // No delimiter found
        // that may or may not be ok
        //
        if (foundBlockEnd && isDelimRequired) Nope
        else if (!foundBlockEnd) Nope
        else {
          //
          // In this case we found a block end, and no delimiter is required
          // so we have enough to be done with the field
          //
          val fieldValue: Maybe[String] = {
            One(fieldRegister.resultString.toString)
          }
          One(new ParseResult(fieldValue, Nope, lmt.longestMatches))
        }
      } else {
        //
        // A delimiter was found
        //
        val fieldValue: Maybe[String] = {
          One(fieldRegister.resultString.toString)
        }
        val delim: Maybe[String] = {
          One(lmt.longestMatchedString)
        }
        One(new ParseResult(fieldValue, delim, lmt.longestMatches))
      }
    }

    TLRegistersPool.returnToPool(fieldRegister)
    result
  }

  def parse(input: DataInputStream, field: DFAField, fieldEsc: DFAField,
    startBlock: DFADelimiter, endBlock: DFADelimiter,
    delimIter: DelimiterIterator, isDelimRequired: Boolean): Maybe[ParseResult] = {
    Assert.invariant(fieldEsc != null)
    Assert.invariant(field != null)
    Assert.invariant(startBlock != null)
    Assert.invariant(endBlock != null)

    removeLeftPadding(input, delimIter)
    val foundStartBlock = parseStartBlock(input, startBlock, delimIter)
    val res = if (!foundStartBlock) {
      super.parse(input, field, delimIter, isDelimRequired)
    } else {
      parseRemainder(input, fieldEsc, startBlock, endBlock, delimIter, isDelimRequired)
    }
    TLRegistersPool.pool.finalCheck

    res
  }

}
