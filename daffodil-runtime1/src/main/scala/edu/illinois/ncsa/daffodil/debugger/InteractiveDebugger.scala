/* Copyright (c) 2012-2015 Tresys Technology, LLC. All rights reserved.
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

package edu.illinois.ncsa.daffodil.debugger

import edu.illinois.ncsa.daffodil.exceptions.Assert
import edu.illinois.ncsa.daffodil.schema.annotation.props.gen.Representation
import edu.illinois.ncsa.daffodil.processors._ ; import edu.illinois.ncsa.daffodil.infoset._
import edu.illinois.ncsa.daffodil.processors.parsers._
import edu.illinois.ncsa.daffodil.xml.XMLUtils
import edu.illinois.ncsa.daffodil.xml.GlobalQName
import edu.illinois.ncsa.daffodil.xml.QName
import edu.illinois.ncsa.daffodil.ExecutionMode
import java.io.File
import jline.console.completer.Completer
import jline.console.completer.StringsCompleter
import jline.console.completer.AggregateCompleter
import edu.illinois.ncsa.daffodil.util.Enum
import edu.illinois.ncsa.daffodil.util.Misc
import edu.illinois.ncsa.daffodil.dsom.ExpressionCompilerClass
import edu.illinois.ncsa.daffodil.dpath.DPathUtil
import edu.illinois.ncsa.daffodil.dpath.NodeInfo
import edu.illinois.ncsa.daffodil.dpath.ExpressionEvaluationException
import edu.illinois.ncsa.daffodil.util.Misc
import edu.illinois.ncsa.daffodil.oolag.ErrorsNotYetRecorded
import edu.illinois.ncsa.daffodil.processors.unparsers.UState
import edu.illinois.ncsa.daffodil.processors.unparsers.Unparser
import edu.illinois.ncsa.daffodil.dsom.RelativePathPastRootError
import edu.illinois.ncsa.daffodil.exceptions.UnsuppressableException
import scala.collection.mutable
import edu.illinois.ncsa.daffodil.dsom.RuntimeSchemaDefinitionError
import edu.illinois.ncsa.daffodil.util.Misc
import edu.illinois.ncsa.daffodil.infoset.InfosetItem
import edu.illinois.ncsa.daffodil.infoset.InfosetElement
import edu.illinois.ncsa.daffodil.infoset.InfosetDocument
import edu.illinois.ncsa.daffodil.infoset.XMLTextInfosetOutputter
import edu.illinois.ncsa.daffodil.processors.parsers.ConvertTextCombinatorParser

abstract class InteractiveDebuggerRunner {
  def init(id: InteractiveDebugger): Unit
  def getCommand: String
  def lineOutput(line: String): Unit
  def fini(): Unit
}

class InteractiveDebugger(runner: InteractiveDebuggerRunner, eCompilers: ExpressionCompilerClass) extends Debugger {

  object DebugState extends Enum {
    sealed abstract trait Type extends EnumValueType
    case object Continue extends Type
    case object Step extends Type
    case object Pause extends Type
    case object Trace extends Type
  }

  case class DebugException(str: String, cause: Throwable) extends java.lang.Exception(str, cause) {
    override def toString = "Debugger error: " + Misc.getSomeMessage(this).get
    def this(str: String) = this(str, null)
  }

  trait Disablable {
    var enabled = true
    def disable = { enabled = false }
    def enable = { enabled = true }
  }

  case class Breakpoint(id: Int, breakpoint: String) extends Disablable {
    var condition: Option[String] = None
  }

  case class Display(id: Int, cmd: Seq[String]) extends Disablable

  object DebuggerConfig {
    /* the max number of lines to tail the infoset, infosetLines <= 0 means print
     * everything */
    var infosetLines: Int = -1

    /* the max number of bytes to display when displaying data */
    var dataLength: Int = 70

    /* the lenght at which to wrap output infoset/data/etc */
    var wrapLength: Int = 80

    /* whether or not to break only on element creation or anytime the element is seen */
    var breakOnlyOnCreation: Boolean = true

    /* whether or not to break on failure */
    var breakOnFailure: Boolean = false

    /* list of breakpoints */
    val breakpoints = collection.mutable.ListBuffer[Breakpoint]()
    var breakpointIndex: Int = 1

    /* list of displays */
    val displays = collection.mutable.ListBuffer[Display]()
    var displayIndex: Int = 1

    /* whether to remove hidden elements when displaying the infoset */
    var removeHidden: Boolean = false

    /* stores the last actual command (i.e. not a "") that was executed */
    var lastCommand: String = ""

    /* stores the full list of commands as typed, even blanks. We need to
     * maintain our own because the jline history ignores empty commands and
     * duplicate commands. That can be configured, but jline's history is much
     * more useful with the history behaving that way. This is really only used
     * for the 'history' command. */
    val history = scala.collection.mutable.ListBuffer[String]()

    /* keeps track of which parse step we're on for trace output */
    var parseStep = 0

    /* how to display data */
    var representation: Representation.Value = Representation.Text
  }

  var debugState: DebugState.Type = DebugState.Pause

  override def init(parser: Parser) {
    runner.init(this)
  }

  override def init(unparser: Unparser) {
    runner.init(this)
  }

  override def fini(parser: Parser) {
    runner.fini
  }

  override def fini(unparser: Unparser) {
    runner.fini
  }

  def debugStep(before: StateForDebugger, after: ParseOrUnparseState, processor: Processor, ignoreBreakpoints: Boolean) {
    ExecutionMode.usingUnrestrictedMode {
      debugState = debugState match {
        case _ if ((after.processorStatus ne Success) && DebuggerConfig.breakOnFailure) => DebugState.Pause
        case DebugState.Continue | DebugState.Trace if !ignoreBreakpoints => {
          findBreakpoint(after, processor) match {
            case Some(bp) => {
              debugPrintln("breakpoint %s: %s   %s".format(bp.id, bp.breakpoint, bp.condition.getOrElse("")))
              DebugState.Pause
            }
            case None => debugState
          }
        }
        case DebugState.Step => DebugState.Pause
        case _ => debugState
      }

      if (debugState == DebugState.Pause || debugState == DebugState.Trace) {
        val dc = DebuggerConfig
        val rawDisplays = dc.displays
        val displays = rawDisplays.filter(_.enabled)
        displays.foreach { d =>
          runCommand(d.cmd, before, after, processor)
        }

        if (after.processorStatus ne Success) {
          debugPrintln("failure:")
          debugPrintln("%s".format(after.diagnostics.head.getMessage()), "  ")
        }

        if (debugState == DebugState.Trace) {
          debugPrintln("----------------------------------------------------------------- " + DebuggerConfig.parseStep)
        }
      }

      DebuggerConfig.parseStep += 1

      while (debugState == DebugState.Pause) {
        val args = readCmd
        debugState = runCommand(args, before, after, processor)
      }
    }
  }

  private def isInteresting(parser: Parser): Boolean = {
    val interesting = parser match {
      case _: ComplexTypeParser => false
      case _: SeqCompParser => false
      case _: SequenceCombinatorParser => false
      case _: AltCompParser => false
      case _: RepParser => false
      case _: OptionalInfixSepParser => false
      case _: ConvertTextCombinatorParser => false
      case _ => true
    }
    interesting
  }

  override def startElement(state: PState, parser: Parser) {
    debugStep(state, state, parser, false)
  }

  override def endElement(state: UState, unparser: Unparser) {
    debugStep(state, state, unparser, false)
  }

  private val parseStack = new mutable.ArrayStack[(StateForDebugger, Parser)]

  override def before(before: PState, parser: Parser) {
    if (isInteresting(parser)) {
      parseStack.push((before.copyStateForDebugger, parser))
    }
    // debugStep(before, before, parser, false)
  }
  override def after(after: PState, parser: Parser) {
    if (isInteresting(parser)) {
      val (before, beforeParser) = parseStack.pop
      Assert.invariant(beforeParser eq parser)
      debugStep(before, after, parser, DebuggerConfig.breakOnlyOnCreation)
    }
  }

  override def beforeRepetition(before: PState, processor: Parser) {
    parseStack.push((before.copyStateForDebugger, processor))
    // debugStep(before, before, processor, false)
  }

  override def afterRepetition(after: PState, processor: Parser) {
    val (_, beforeParser) = parseStack.pop
    Assert.invariant(beforeParser eq processor)
    // debugStep(before.get, after, processor, false)
  }

  private def isInteresting(unparser: Unparser): Boolean = {
    true
  }

  private val unparseStack = new mutable.ArrayStack[(StateForDebugger, Unparser)]

  override def before(before: UState, unparser: Unparser) {
    if (isInteresting(unparser)) {
      unparseStack.push((before.copyStateForDebugger, unparser))
    }
    while (debugState == DebugState.Pause) {
      val args = readCmd
      debugState = runCommand(args, before, before, unparser)
    }
  }

  override def after(after: UState, unparser: Unparser) {
    if (isInteresting(unparser)) {
      val (before, beforeUnparser) = unparseStack.pop
      Assert.invariant(beforeUnparser eq unparser)
      debugStep(before, after, unparser, false)
    }
  }

  private def readCmd(): Seq[String] = {
    val input = runner.getCommand.trim

    DebuggerConfig.history += input

    val cmd = input match {
      case "" => {
        DebuggerConfig.lastCommand
      }
      case _ => {
        DebuggerConfig.lastCommand = input
        input
      }
    }
    cmd.split(" ").filter(_ != "")
  }

  private val debuggerQName = GlobalQName(Some("daf"), "debugger", XMLUtils.dafintURI)

  /**
   * Here the debugger depends on being able to evaluate expressions that might run into
   * problems like asking for data from elements that don't exist yet or have no values yet.
   *
   * There also can be compilation errors if the expressions aren't well formed or have type errors in them (such as
   * they don't return a boolean value).
   */
  private def evaluateBooleanExpression(expression: String, state: ParseOrUnparseState, processor: Processor): Boolean = {
    val context = state.getContext()
    try {
      //
      // compile the expression
      //
      val compiledExpr = try {
        eCompilers.JBoolean.compile(debuggerQName,
          NodeInfo.Boolean, expression, processor.context.namespaces, context.dpathCompileInfo, false)
      } catch {
        //
        // These are compile-time errors for the expression compilation
        //
        case errs: ErrorsNotYetRecorded => {
          debugPrintln(errs)
          throw errs
        }
      }
      //
      // evaluate the expression, and catch ways it can fail just because this is the debugger and it
      // isn't necessarily evaluating the expression in sensible places.
      //
      // Note also that the debugger does not use Evaluatable around the compiled expression. This is because
      // Evaluatable is really designed to be called from parsers/unparsers.
      //
      try {
        val res = compiledExpr.evaluate(state)
        res match {
          case b: java.lang.Boolean => b.booleanValue()
          case _ => false
        }
      } catch {
        case s: scala.util.control.ControlThrowable => throw s
        case u: UnsuppressableException => throw u
        case _: ExpressionEvaluationException | _: InfosetException | _: VariableException => {
          // ?? How do we discern for the user whether this is a problem with their expression or
          // the infoset is just not populated with the things the expression references yet?
          state.setSuccess()
          false
        }
        //
        // Most errors are coming back here as RSDE because that's what they get upconverted into.
        // Most expression problems are considered SDE.
        //
        case rsde: RuntimeSchemaDefinitionError => {
          // println(rsde.getMessage())
          state.setSuccess()
          false
        }
      }
    } catch {
      case s: scala.util.control.ControlThrowable => throw s
      case u: UnsuppressableException => throw u
      case e: Throwable => {
        println("caught throwable " + Misc.getNameFromClass(e) + ": " + Misc.getSomeMessage(e).get)
        state.setSuccess()
        false
      }
    }
  }

  private def findBreakpoint(state: ParseOrUnparseState, processor: Processor): Option[Breakpoint] = {
    val foundBreakpoint =
      DebuggerConfig.breakpoints
        .filter(_.enabled)
        .filter { bp =>

          //
          // Two syntaxes for breakpoints are accepted.
          // one is extended QNames e.g., foo, or pre:foo, or {uri}foo
          // the other is schema component paths like foo::bar::baz
          //
          val tryBPQName = QName.refQNameFromExtendedSyntax(bp.breakpoint)
          if (tryBPQName.isFailure) {
            //
            // Breakpoint specified by path syntax
            //
            bp.breakpoint == processor.context.path
          } else {
            //
            // must be the extended QName case.
            //
            processor.context match {
              case erd: ElementRuntimeData => {
                val elemQName = erd.namedQName
                val bpqnx = tryBPQName.get
                //
                // If the user provided the {uri}foo style syntax
                // we just need the namespace and name to match.
                //
                if (bpqnx.local == elemQName.local &&
                  bpqnx.namespace == elemQName.namespace) {
                  true
                } else {
                  //
                  // usage must have been just a QName e.g., foo:bar
                  // for the breakpoint, or mostlikely, just a local name bar.
                  //
                  val bpQNameString = bpqnx.toQNameString
                  val bpqn = processor.context.resolveQName(bpQNameString)
                  val isMatch = bpqn.toStepQName.matches(elemQName)
                  if (isMatch)
                    true
                  else {
                    //
                    // finally, if the bp was just specified as a local name
                    // then ok so long as the local name part matches.
                    //
                    // TODO: it would be good to know if this bp name is ambiguous
                    // In this case it will match ANY element having that local
                    // name. But if you want to be more selective of just the
                    // specific element in a specific namespace then you can use
                    // the extended QName syntax, or just a prefix on it.
                    val isLocalMatch = bpqnx.local == elemQName.local
                    isLocalMatch
                  }
                }
              }
              case _ => false
            }
          }
        }
        .find { bp =>
          bp.condition match {
            case Some(expression) => evaluateBooleanExpression(expression, state, processor)
            case None => true
          }
        }
    foundBreakpoint
  }

  private def runCommand(cmd: Seq[String], before: StateForDebugger, after: ParseOrUnparseState, processor: Processor): DebugState.Type = {
    try {
      DebugCommandBase(cmd, before, after, processor)
    } catch {
      case e: DebugException => {
        debugPrintln(e)
        DebugState.Pause
      }
    }
  }

  private def debugPrintln(obj: Any = "", prefix: String = "") {
    obj.toString.split("\n").foreach { line =>
      {
        val out = "%s%s".format(prefix, line)
        runner.lineOutput(out)
      }
    }
  }

  private def debugPrettyPrintXML(ie: InfosetElement) {
    val sw = new java.io.StringWriter()
    val xml = new XMLTextInfosetOutputter(sw)
    ie.visit(xml, DebuggerConfig.removeHidden)
    debugPrintln(sw.toString)
  }

  /**********************************/
  /**          Commands            **/
  /**********************************/

  abstract class DebugCommand {
    val name: String
    lazy val short: String = name(0).toString
    val desc: String
    val longDesc: String
    val subcommands: Seq[DebugCommand] = Seq()
    val hidden = false

    def apply(args: Seq[String], prestate: StateForDebugger, state: ParseOrUnparseState, processor: Processor): DebugState.Type = {
      validate(args)
      act(args, prestate, state, processor)
    }

    def validate(args: Seq[String]): Unit

    def act(args: Seq[String], prestate: StateForDebugger, state: ParseOrUnparseState, processor: Processor): DebugState.Type

    override def equals(that: Any): Boolean = {
      that match {
        case str: String => (str == name || str == short)
        case _ => super.equals(that)
      }
    }

    def help(args: Seq[String]): Unit = {

      // this wraps a line of text to a maximum width, breaking on space
      def wrapLine(line: String, width: Int): List[String] = {
        if (line.length == 0) {
          Nil
        } else {
          val spaceIndex = line.lastIndexOf(" ", width)
          if (line.length < width || spaceIndex == -1) {
            List(line)
          } else {
            val wrapped = line.take(spaceIndex) :: wrapLine(line.drop(spaceIndex + 1), width)
            wrapped
          }
        }
      }

      args.length match {
        case 0 => {
          val visibleSubcommands = subcommands.filter(!_.hidden)
          if (name != "") {
            debugPrintln("%s".format(longDesc))
            if (!visibleSubcommands.isEmpty) {
              debugPrintln()
              debugPrintln("Subcommands:")
            }
          }
          val maxLen = visibleSubcommands.foldLeft(0) { (i, c) => i.max(c.name.length) }
          val formatString = "  %-" + maxLen + "s  %s"
          visibleSubcommands.foreach(c => {
            val descColumnWidth = 75
            val descLines = wrapLine(c.desc, descColumnWidth - maxLen)
            val prefixes = c.name :: List.fill(descLines.length - 1)("")
            prefixes.zip(descLines).foreach { case (p, d) => debugPrintln(formatString.format(p, d)) }
          })
        }
        case _ => {
          val subcmd = args.head
          val subcmdArgs = args.tail
          subcommands.find(_ == subcmd) match {
            case Some(cmd) => cmd.help(subcmdArgs)
            case None => throw new DebugException("unknown command: %s".format(subcmd))
          }
        }
      }
    }

    class DebugCommandCompleter(dc: DebugCommand) extends Completer {
      val subcommandsCompleter = new AggregateCompleter(dc.subcommands.sortBy(_.name).map(_.completer): _*)

      def getCompleteString(args: String) = {
        // just remove leading whitespace
        val trimmed = args.replaceAll("^\\s+", "")
        trimmed
      }

      def complete(buffer: String, cursor: Int, candidates: java.util.List[CharSequence]): Int = {
        val cmds = buffer.replaceAll("^\\s+", "").split("(?= )", 2).toList
        val (cmd, args) = cmds match {
          case c :: rest => rest match {
            case a :: Nil => (c, a)
            case Nil => (c, "")
            case _ => Assert.impossible("cmd/args were split incorrectly")
          }
          case Nil => ("", "")
        }

        if (args != "") {
          if (dc == cmd) {
            val completeString = getCompleteString(args)
            val subcandidates = new java.util.ArrayList[CharSequence]
            val newCursor = subcommandsCompleter.complete(completeString, cursor, subcandidates)
            val seq = scala.collection.JavaConversions.asScalaBuffer(subcandidates)
            seq.foreach(c => candidates.add(c))
            buffer.lastIndexOf(completeString) + newCursor
          } else {
            -1
          }
        } else {
          if (dc.name.startsWith(cmd)) {
            candidates.add(dc.name + " ")
            buffer.lastIndexOf(cmd)
          } else {
            -1
          }
        }
      }
    }

    def completer: Completer = {
      if (subcommands.isEmpty) {
        new StringsCompleter(name)
      } else {
        new DebugCommandCompleter(this)
      }
    }

    // This ensures that there are no naming conflicts (e.g. short form names
    // conflict). This is really just a sanity check, and really only needs to
    // be run whenever names change or new commands are added.

    // Uncomment this and the DebugCommandBase.checkNameConflicts line to do a
    // check when changes are made.
    //
    /*
    def checkNameConflicts() {
      val allNames = subcommands.map(_.name) ++ subcommands.filter{ sc => sc.name != sc.short }.map(_.short)
      val duplicates = allNames.groupBy{ n => n }.filter{ case(_, l) => l.size > 1 }.keys
      if (duplicates.size > 0) {
        Assert.invariantFailed("Duplicate debug commands found in '%s' command: ".format(name) + duplicates)
      }
      subcommands.foreach(_.checkNameConflicts)
    }
    */
  }

  //DebugCommandBase.checkNameConflicts

  trait DebugCommandValidateSubcommands { self: DebugCommand =>
    override def validate(args: Seq[String]) {
      if (args.size == 0) {
        throw new DebugException("no command specified")
      }
      val subcmd = args.head
      val subcmdArgs = args.tail
      subcommands.find(_ == subcmd) match {
        case Some(c) => c.validate(subcmdArgs)
        case None => {
          throw new DebugException("undefined command: %s".format(subcmd))
        }
      }
    }
  }

  trait DebugCommandValidateZeroArgs { self: DebugCommand =>
    override def validate(args: Seq[String]) {
      if (args.length != 0) {
        throw new DebugException("%s command requires zero arguments".format(name))
      }
    }
  }

  trait DebugCommandValidateSingleArg { self: DebugCommand =>
    override def validate(args: Seq[String]) {
      if (args.length != 1) {
        throw new DebugException("%s command requires a single argument".format(name))
      }
    }
  }

  trait DebugCommandValidateBoolean { self: DebugCommand =>
    override def validate(args: Seq[String]) {
      if (args.size != 1) {
        throw new DebugException("%s command requires a single argument".format(name))
      } else {
        val state = args.head
        if (state != "true" && state != "1" && state != "false" && state != "0") {
          throw new DebugException("argument must be true/false or 1/0")
        }
      }
    }
  }

  trait DebugCommandValidateInt { self: DebugCommand =>
    override def validate(args: Seq[String]) {
      if (args.size != 1) {
        throw new DebugException("%s command requires a single argument".format(name))
      } else {
        try {
          args.head.toInt
        } catch {
          case _: NumberFormatException => throw new DebugException("integer argument is required")
        }
      }
    }
  }

  trait DebugCommandValidateOptionalArg { self: DebugCommand =>
    override def validate(args: Seq[String]) {
      if (args.size > 1) {
        throw new DebugException("%s command zero or one arguments".format(name))
      }
    }
  }

  object DebugCommandBase extends DebugCommand with DebugCommandValidateSubcommands {
    val name = ""
    val desc = ""
    val longDesc = ""
    override lazy val short = ""
    override val subcommands = Seq(Break, Clear, Complete, Condition, Continue, Delete, Disable, Display, Enable, Eval, Help, History, Info, Quit, Set, Step, Trace)

    def act(args: Seq[String], prestate: StateForDebugger, state: ParseOrUnparseState, processor: Processor): DebugState.Type = {
      val subcmd = args.head
      val subcmdArgs = args.tail
      val subcmdActor = subcommands.find(_ == subcmd).get
      val newState = subcmdActor.act(subcmdArgs, prestate, state, processor)
      newState
    }

    override def completer: Completer = {
      new DebugCommandCompleter(this) {
        override def complete(buffer: String, cursor: Int, candidates: java.util.List[CharSequence]): Int = {
          val cmd = buffer.replaceAll("^\\s+", "")
          val subcandidates = new java.util.ArrayList[CharSequence]
          val newCursor = subcommandsCompleter.complete(cmd, cursor, subcandidates)
          val seq = scala.collection.JavaConversions.asScalaBuffer(subcandidates)
          seq.foreach(c => candidates.add(c))
          buffer.lastIndexOf(cmd) + newCursor
        }
      }
    }

    object Break extends DebugCommand with DebugCommandValidateSingleArg {
      val name = "break"
      val desc = "create a breakpoint"
      val longDesc = """|Usage: b[reak] <element_id>
                        |
                        |Create a breakpoint, causing the debugger to stop when the element
                        |with the <element_id> name is created.
                        |
                        |Example: break foo""".stripMargin

      def act(args: Seq[String], prestate: StateForDebugger, state: ParseOrUnparseState, processor: Processor): DebugState.Type = {
        val bp = new Breakpoint(DebuggerConfig.breakpointIndex, args.head)
        DebuggerConfig.breakpoints += bp
        debugPrintln("%s: %s".format(bp.id, bp.breakpoint))
        DebuggerConfig.breakpointIndex += 1
        DebugState.Pause
      }
    }

    object Clear extends DebugCommand with DebugCommandValidateZeroArgs {
      val name = "clear"
      val desc = "clear the screen"
      val longDesc = """|Usage: cl[ear]
                        |
                        |Clear the screen.""".stripMargin
      override lazy val short = "cl"

      def act(args: Seq[String], prestate: StateForDebugger, state: ParseOrUnparseState, processor: Processor): DebugState.Type = {
        print(27: Char)
        print('[')
        print("2J")
        print(27: Char)
        print('[')
        print("1;1H")

        DebugState.Pause
      }
    }

    object Complete extends DebugCommand with DebugCommandValidateZeroArgs {
      val name = "complete"
      val desc = "disable all debugger actions and continue"
      val longDesc = """|Usage: comp[lete]
                        |
                        |Continue parsing the input data until parsing is complete. All
                        |breakpoints are ignored.""".stripMargin
      override lazy val short = "comp"

      def act(args: Seq[String], prestate: StateForDebugger, state: ParseOrUnparseState, processor: Processor): DebugState.Type = {
        DebuggerConfig.breakpoints.foreach(_.disable)
        DebuggerConfig.displays.foreach(_.disable)
        DebugState.Continue
      }
    }

    object Condition extends DebugCommand {
      val name = "condition"
      val desc = "set a DFDL expression to stop at breakpoint"
      val longDesc = """|Usage: cond[ition] <breakpoint_id> <dfdl_expression>
                        |
                        |Set a condition on a specified breakpoint. When a breakpoint
                        |is encountered, the debugger only pauses if the DFDL expression
                        |evaluates to true. If the result of the DFDL expression is not
                        |a boolean value, it is treated as false.
                        |
                        |Example: condition 1 dfdl:occursIndex() eq 3""".stripMargin
      override lazy val short = "cond"

      override def validate(args: Seq[String]) {
        if (args.length < 2) {
          throw new DebugException("condition command requires a breakpoint id and a DFDL expression")
        }

        val idArg = args.head
        val id = try {
          idArg.toInt
        } catch {
          case _: NumberFormatException => throw new DebugException("integer argument required")
        }

        DebuggerConfig.breakpoints.find(_.id == id).getOrElse {
          throw new DebugException("breakpoint %d not found".format(id))
        }
      }

      def act(args: Seq[String], prestate: StateForDebugger, state: ParseOrUnparseState, processor: Processor): DebugState.Type = {
        val id = args.head.toInt
        val expression = args.tail.mkString(" ")
        val b = DebuggerConfig.breakpoints.find(_.id == id).get
        b.condition = Some(expression)
        debugPrintln("%s: %s   %s".format(b.id, b.breakpoint, expression))
        DebugState.Pause
      }
    }

    object Continue extends DebugCommand with DebugCommandValidateZeroArgs {
      val name = "continue"
      val desc = "continue parsing until a breakpoint is found"
      val longDesc = """|Usage: c[ontinue]
                        |
                        |Continue parsing the input data until a breakpoint is encountered. At
                        |which point, pause parsing and display a debugger console to the user.""".stripMargin

      def act(args: Seq[String], prestate: StateForDebugger, state: ParseOrUnparseState, processor: Processor): DebugState.Type = {
        DebugState.Continue
      }
    }

    object Delete extends DebugCommand with DebugCommandValidateSubcommands {
      val name = "delete"
      val desc = "delete breakpoints and displays"
      val longDesc = """|Usage: d[elete] <type> <id>
                        |
                        |Remove a breakpoint or display.
                        |
                        |Example: delete breakpoint 1
                        |         delete display 1""".stripMargin
      override val subcommands = Seq(DeleteBreakpoint, DeleteDisplay)

      def act(args: Seq[String], prestate: StateForDebugger, state: ParseOrUnparseState, processor: Processor): DebugState.Type = {
        val subcmd = args.head
        val subcmdArgs = args.tail
        subcommands.find(_ == subcmd).get.act(subcmdArgs, prestate, state, processor)
        DebugState.Pause
      }

      object DeleteBreakpoint extends DebugCommand with DebugCommandValidateInt {
        val name = "breakpoint"
        val desc = "delete a breakpoint"
        val longDesc = """|Usage: d[elete] b[reakpoint] <breakpoint_id>
                          |
                          |Remove a breakpoint created using the 'breakpoint' command.
                          |
                          |Example: delete breakpoint 1""".stripMargin

        def act(args: Seq[String], prestate: StateForDebugger, state: ParseOrUnparseState, processor: Processor): DebugState.Type = {
          val id = args.head.toInt
          DebuggerConfig.breakpoints.find(_.id == id) match {
            case Some(b) => DebuggerConfig.breakpoints -= b
            case None => throw new DebugException("breakpoint %d not found".format(id))
          }
          DebugState.Pause
        }
      }

      object DeleteDisplay extends DebugCommand with DebugCommandValidateInt {
        val name = "display"
        val desc = "delete a display"
        val longDesc = """|Usage: d[elete] di[splay] <display_id>
                          |
                          |Remove a display created using the 'display' command.
                          |
                          |Example: delete display 1""".stripMargin
        override lazy val short = "di"

        def act(args: Seq[String], prestate: StateForDebugger, state: ParseOrUnparseState, processor: Processor): DebugState.Type = {
          val id = args.head.toInt
          DebuggerConfig.displays.find(d => d.id == id) match {
            case Some(d) => DebuggerConfig.displays -= d
            case None => throw new DebugException("display %d not found".format(id))
          }
          DebugState.Pause
        }
      }
    }

    object Disable extends DebugCommand with DebugCommandValidateSubcommands {
      val name = "disable"
      val desc = "disable breakpoints and displays"
      val longDesc = """|Usage: dis[able] <type> <id>
                        |
                        |Disable a breakpoint or display.
                        |
                        |Example: disable breakpoint 1
                        |         disable display 1""".stripMargin
      override lazy val short = "dis"
      override val subcommands = Seq(DisableBreakpoint, DisableDisplay)

      def act(args: Seq[String], prestate: StateForDebugger, state: ParseOrUnparseState, processor: Processor): DebugState.Type = {
        val subcmd = args.head
        val subcmdArgs = args.tail
        subcommands.find(_ == subcmd).get.act(subcmdArgs, prestate, state, processor)
        DebugState.Pause
      }

      object DisableBreakpoint extends DebugCommand with DebugCommandValidateInt {
        val name = "breakpoint"
        val desc = "disable a breakpoint"
        val longDesc = """|Usage: dis[able] b[reakpoint] <breakpoint_id>
                          |
                          |Disable a breakpoint with the specified id. This causes the breakpoint
                          |to be skipped during debugging.
                          |
                          |Example: disable breakpoint 1""".stripMargin

        def act(args: Seq[String], prestate: StateForDebugger, state: ParseOrUnparseState, processor: Processor): DebugState.Type = {
          val id = args.head.toInt
          DebuggerConfig.breakpoints.find(_.id == id) match {
            case Some(b) => b.disable
            case None => throw new DebugException("%d is not a valid breakpoint id".format(id))
          }
          DebugState.Pause
        }
      }

      object DisableDisplay extends DebugCommand with DebugCommandValidateInt {
        val name = "display"
        val desc = "disable a display"
        val longDesc = """|Usage: d[isable] di[splay] <display_id>
                          |
                          |Disable a display with the specified id. This causes the display command
                          |to be skipped during debugging.
                          |
                          |Example: disable display 1""".stripMargin
        override lazy val short = "di"

        def act(args: Seq[String], prestate: StateForDebugger, state: ParseOrUnparseState, processor: Processor): DebugState.Type = {
          val id = args.head.toInt
          DebuggerConfig.displays.find(_.id == id) match {
            case Some(d) => d.disable
            case None => throw new DebugException("%d is not a valid display id".format(id))
          }
          DebugState.Pause
        }
      }
    }

    object Display extends DebugCommand with DebugCommandValidateSubcommands {
      val name = "display"
      val desc = "show value of expression each time program stops"
      val longDesc = """|Usage: di[splay] <debugger_command>
                        |
                        |Execute a debugger command (limited to eval or info) every time a debugger
                        |console is displayed to the user.
                        |
                        |Example: display info infoset""".stripMargin
      override lazy val short = "di"
      override val subcommands = Seq(Eval, Info)

      def act(args: Seq[String], prestate: StateForDebugger, state: ParseOrUnparseState, processor: Processor): DebugState.Type = {
        DebuggerConfig.displays += new Display(DebuggerConfig.displayIndex, args)
        DebuggerConfig.displayIndex += 1
        DebugState.Pause
      }
    }

    object Enable extends DebugCommand with DebugCommandValidateSubcommands {
      val name = "enable"
      val desc = "enable breakpoints and displays"
      val longDesc = """|Usage: e[nable] <type> <id>
                        |
                        |Enable a breakpoint or display.
                        |
                        |Example: enable breakpoint 1
                        |         enable display 1""".stripMargin
      override val subcommands = Seq(EnableBreakpoint, EnableDisplay)

      def act(args: Seq[String], prestate: StateForDebugger, state: ParseOrUnparseState, processor: Processor): DebugState.Type = {
        val subcmd = args.head
        val subcmdArgs = args.tail
        subcommands.find(_ == subcmd).get.act(subcmdArgs, prestate, state, processor)
        DebugState.Pause
      }

      object EnableBreakpoint extends DebugCommand with DebugCommandValidateInt {
        val name = "breakpoint"
        val desc = "enable a breakpoint"
        val longDesc = """|Usage: e[nable] b[reakpoint] <breakpoint_id>
                          |
                          |Enable a breakpoint with the specified id. This causes the breakpoint
                          |to be evaluated during debugging.
                          |
                          |Example: enable breakpoint 1""".stripMargin

        def act(args: Seq[String], prestate: StateForDebugger, state: ParseOrUnparseState, processor: Processor): DebugState.Type = {
          val id = args.head.toInt
          DebuggerConfig.breakpoints.find(_.id == id) match {
            case Some(b) => b.enable
            case None => throw new DebugException("%d is not a valid breakpoint id".format(id))
          }
          DebugState.Pause
        }
      }

      object EnableDisplay extends DebugCommand with DebugCommandValidateInt {
        val name = "display"
        val desc = "enable a display"
        val longDesc = """|Usage: e[nable] di[splay] <display_id>
                          |
                          |Enable a display with the specified id. This causes the display command
                          |to be run during debugging.
                          |
                          |Example: enable display 1""".stripMargin

        def act(args: Seq[String], prestate: StateForDebugger, state: ParseOrUnparseState, processor: Processor): DebugState.Type = {
          val id = args.head.toInt
          DebuggerConfig.displays.find(_.id == id) match {
            case Some(d) => d.enable
            case None => throw new DebugException("%d is not a valid display id".format(id))
          }
          DebugState.Pause
        }
      }
    }

    object Eval extends DebugCommand {
      val name = "eval"
      val desc = "evaluate a DFDL expression"
      override lazy val short = "ev"
      val longDesc = """|Usage: ev[al] <dfdl_expression>
                        |
                        |Evaluate a DFDL expression.
                        |
                        |Example: eval dfdl:occursIndex()""".stripMargin

      override def validate(args: Seq[String]) {
        if (args.size == 0) {
          throw new DebugException("eval requires a DFDL expression")
        }
      }

      def act(args: Seq[String], prestate: StateForDebugger, state: ParseOrUnparseState, processor: Processor): DebugState.Type = {
        val expressionList = args
        val expression = expressionList.mkString(" ")

        if (!state.hasInfoset) {
          debugPrintln("eval: There is no infoset currently.")
          return DebugState.Pause
        }
        val element = state.infoset match {
          case e: InfosetElement => e
          case d: InfosetDocument => d.getRootElement()
        }
        // this adjustment is so that automatic display of ".." doesn't fail
        // for the root element.
        val adjustedExpression =
          if ((element.parent eq null) && (expression == "..")) "."
          else expression
        val context = state.getContext()
        val namespaces = context.namespaces
        val expressionWithBraces =
          if (!DPathUtil.isExpression(adjustedExpression)) "{ " + adjustedExpression + " }"
          else adjustedExpression
        val isEvaluatedAbove = false
        try {
          val compiledExpression = eCompilers.AnyRef.compile(debuggerQName,
            NodeInfo.AnyType, expressionWithBraces, namespaces, context.dpathCompileInfo,
            isEvaluatedAbove)
          val res = compiledExpression.evaluate(state)
          res match {
            case ie: InfosetElement => debugPrettyPrintXML(ie)
            case nodeSeq: Seq[Any] => nodeSeq.foreach { a =>
              a match {
                case ie: InfosetElement => debugPrettyPrintXML(ie)
                case _ => debugPrintln(a)
              }
            }
            case _ => debugPrintln(res)
          }
        } catch {
          case e: ErrorsNotYetRecorded => {
            val diags = e.diags
            val newDiags = diags.flatMap { d =>
              d match {
                case rel: RelativePathPastRootError => Nil
                case _ => List(d)
              }
            }
            if (!newDiags.isEmpty) {
              val ex = new ErrorsNotYetRecorded(newDiags)
              throw new DebugException("expression evaluation failed: %s".format(Misc.getSomeMessage(ex).get))
            }
          }
          case s: scala.util.control.ControlThrowable => throw s
          //          case e: ExpressionEvaluationException => println(e)
          //          case e: InfosetException => println(e)
          //          case e: VariableException => println(e)
          case e: Throwable => {
            val ex = e // just so we can see it in the debugger.
            throw new DebugException("expression evaluation failed: %s".format(Misc.getSomeMessage(ex).get))
          }
        }
        DebugState.Pause
      }
    }

    object Help extends DebugCommand {
      val name = "help"
      val desc = "display information about a command"
      val longDesc = """|Usage: h[elp] [command]
                        |
                        |Display help. If a command is given, display help information specific
                        |to that command and its subcommands.
                        |
                        |Example: help info""".stripMargin
      override val subcommands = Seq(Break, Clear, Complete, Condition, Continue, Delete, Disable, Display, Enable, Eval, History, Info, Quit, Set, Step, Trace)

      override def validate(args: Seq[String]) {
        // no validation
      }

      def act(args: Seq[String], prestate: StateForDebugger, state: ParseOrUnparseState, processor: Processor): DebugState.Type = {
        DebugCommandBase.help(args)
        DebugState.Pause
      }
    }

    object History extends DebugCommand with DebugCommandValidateOptionalArg {
      val name = "history"
      override lazy val short = "hi"
      val desc = "display the history of commands"
      val longDesc = """|Usage: hi[story] [outfile]
                        |
                        |Display the history of commands. If an argument is given, write
                        |the history to the specified file rather then printing it to the
                        |screen.
                        |
                        |Example: history
                        |         history out.txt""".stripMargin

      def act(args: Seq[String], prestate: StateForDebugger, state: ParseOrUnparseState, processor: Processor): DebugState.Type = {
        args.size match {
          case 0 => {
            debugPrintln("%s:".format(name))
            DebuggerConfig.history.zipWithIndex.foreach { case (cmd, index) => debugPrintln("%d: %s".format(index, cmd), "  ") }

          }
          case 1 => {
            try {
              val path =
                if (args.head.startsWith("~" + File.separator)) {
                  System.getProperty("user.home") + args.head.substring(1)
                } else {
                  args.head
                }
              val fw = new java.io.FileWriter(path)
              val bw = new java.io.BufferedWriter(fw)
              // use .init to drop the 'history outfile' command, we want
              // something that can be easily provided to the InteractiveDebugger constructor
              DebuggerConfig.history.init.foreach(cmd => {
                bw.write(cmd)
                bw.newLine()
              })
              bw.close()
              fw.close()
              debugPrintln("%s: written to %s".format(name, args.head))
            } catch {
              case s: scala.util.control.ControlThrowable => throw s
              case u: UnsuppressableException => throw u
              case e: Throwable => throw new DebugException("failed to write history file: " + e.getMessage())
            }
          }
        }
        DebugState.Pause
      }
    }

    object Info extends DebugCommand {
      val name = "info"
      val desc = "display information"
      val longDesc = """|Usage: i[nfo] <item>...
                        |
                        |Print internal information to the console. <item> can be specified
                        |multiple times to display multiple pieces of information.
                        |
                        |Example: info data infoset""".stripMargin
      override val subcommands = Seq(InfoArrayIndex, InfoBitLimit, InfoBitPosition, InfoBreakpoints, InfoChildIndex, InfoData, InfoDelimiterStack, InfoDiff, InfoDiscriminator, InfoDisplays, InfoFoundDelimiter, InfoGroupIndex, InfoInfoset, InfoOccursBounds, InfoParser, InfoUnparser, InfoPath)

      override def validate(args: Seq[String]) {
        if (args.size == 0) {
          throw new DebugException("one or more commands are required")
        }
        args.foreach(arg => {
          subcommands.find(_ == arg) match {
            case Some(c) => c.validate(Seq())
            case None => throw new DebugException("undefined info command: %s".format(arg))
          }
        })
      }

      def act(args: Seq[String], prestate: StateForDebugger, state: ParseOrUnparseState, processor: Processor): DebugState.Type = {
        args.foreach(arg => {
          val action = subcommands.find(_ == arg).get
          action.act(Seq(), prestate, state, processor)
        })
        DebugState.Pause
      }

      override def completer = new InfoCommandCompleter(this)

      class InfoCommandCompleter(dc: DebugCommand) extends DebugCommandCompleter(dc) {
        override def getCompleteString(args: String) = {
          val lastInfoCommand =
            if (args.endsWith(" ")) {
              "" // this allows the subcommand completers to match anything
            } else {
              // otherwise, it will only match against the last info argument,
              // so 'info foo bar inf\t' will match 'inf' to infoset. The
              // default getComplteString would match against 'foo bar inf',
              // which wouldn't find anything
              args.split("\\s+").last
            }
          lastInfoCommand
        }
      }

      object InfoArrayIndex extends DebugCommand with DebugCommandValidateZeroArgs {
        val name = "arrayIndex"
        override lazy val short = "ai"
        val desc = "display the current array limit"
        val longDesc = desc
        def act(args: Seq[String], prestate: StateForDebugger, state: ParseOrUnparseState, processor: Processor): DebugState.Type = {
          if (state.arrayPos != -1) {
            debugPrintln("%s: %d".format(name, state.arrayPos))
          } else {
            debugPrintln("%s: not in an array".format(name))
          }
          DebugState.Pause
        }
      }

      object InfoBitLimit extends DebugCommand with DebugCommandValidateZeroArgs {
        val name = "bitLimit"
        override lazy val short = "bl"
        val desc = "display the current bit limit"
        val longDesc = desc
        def act(args: Seq[String], prestate: StateForDebugger, state: ParseOrUnparseState, processor: Processor): DebugState.Type = {
          if (state.bitLimit0b.isDefined) {
            debugPrintln("%s: %d".format(name, state.bitLimit0b.get))
          } else {
            debugPrintln("%s: no bit limit set".format(name))
          }
          DebugState.Pause
        }
      }

      object InfoBitPosition extends DebugCommand with DebugCommandValidateZeroArgs {
        val name = "bitPosition"
        override lazy val short = "bp"
        val desc = "display the current bit position"
        val longDesc = desc
        def act(args: Seq[String], prestate: StateForDebugger, state: ParseOrUnparseState, processor: Processor): DebugState.Type = {
          if (state.bitPos != -1) {
            debugPrintln("%s: %d".format(name, state.bitPos))
          } else {
            debugPrintln("%s: no bit position set".format(name))
          }
          DebugState.Pause
        }
      }

      object InfoBreakpoints extends DebugCommand with DebugCommandValidateZeroArgs {
        val name = "breakpoints"
        val desc = "display the current breakpoints"
        val longDesc = desc
        def act(args: Seq[String], prestate: StateForDebugger, state: ParseOrUnparseState, processor: Processor): DebugState.Type = {
          if (DebuggerConfig.breakpoints.size == 0) {
            debugPrintln("%s: no breakpoints set".format(name))
          } else {
            debugPrintln("%s:".format(name))
            DebuggerConfig.breakpoints.foreach { b =>
              {
                val enabledStr = if (b.enabled) "" else "*"
                debugPrintln("%s%s: %s   %s".format(b.id, enabledStr, b.breakpoint, b.condition.getOrElse("")), "  ")
              }
            }
          }
          DebugState.Pause
        }
      }

      object InfoChildIndex extends DebugCommand with DebugCommandValidateZeroArgs {
        val name = "childIndex"
        override lazy val short = "ci"
        val desc = "display the current child index"
        val longDesc = desc
        def act(args: Seq[String], prestate: StateForDebugger, state: ParseOrUnparseState, processor: Processor): DebugState.Type = {
          if (state.childPos != -1) {
            debugPrintln("%s: %d".format(name, state.childPos))
          } else {
            debugPrintln("%s: not a child element".format(name))
          }
          DebugState.Pause
        }
      }

      object InfoData extends DebugCommand with DebugCommandValidateZeroArgs {
        val name = "data"
        val desc = "display the input/output data"
        val longDesc = desc

        def printData(rep: Option[Representation], l: Int, prestate: StateForDebugger, state: ParseOrUnparseState, processor: Processor) {
          // val length = if (l <= 0) Int.MaxValue - 1 else l
          val dataLoc = prestate.currentLocation.asInstanceOf[DataLoc]
          val lines = dataLoc.dump(rep, prestate.currentLocation, state)
          debugPrintln(lines, "  ")
        }

        def act(args: Seq[String], prestate: StateForDebugger, state: ParseOrUnparseState, processor: Processor): DebugState.Type = {
          debugPrintln("%s:".format(name))
          val rep = if (args.size > 0) {
            args(0).toLowerCase match {
              case "t" => Some(Representation.Text)
              case "b" => Some(Representation.Binary)
              case _ =>
                throw new DebugException("uknown representation: %s. Must be one of 't' for text or 'b' for binary".format(args(0)))
            }
          } else {
            if (state.hasInfoset) {
              state.infoset match {
                case e: DIElement => Some(e.runtimeData.impliedRepresentation)
              }
            } else {
              None
            }
          }

          val len = if (args.size > 1) {
            try {
              args(1).toInt
            } catch {
              case _: NumberFormatException => throw new DebugException("data length must be an integer")
            }
          } else {
            DebuggerConfig.dataLength
          }

          printData(rep, len, prestate, state, processor)
          DebugState.Pause
        }
      }

      object InfoDelimiterStack extends DebugCommand with DebugCommandValidateZeroArgs {
        val name = "delimiterStack"
        val desc = "display the delimiter stack"
        val longDesc = desc
        override lazy val short = "ds"

        def act(args: Seq[String], prestate: StateForDebugger, state: ParseOrUnparseState, processor: Processor): DebugState.Type = {
          debugPrintln("%s:".format(name))

          state match {
            case pstate: PState => {
              var i = 0
              while (i < pstate.mpstate.delimiters.length) {
                val typeString = if (i < pstate.mpstate.delimitersLocalIndexStack.top) "remote:" else "local: "
                val delim = pstate.mpstate.delimiters(i)
                debugPrintln("%s %s (%s)".format(typeString, delim.lookingFor, delim.delimType.toString.toLowerCase), "  ")
                i += 1
              }
            }
            case ustate: UState => {
              // TODO
            }
            case _ => Assert.impossibleCase()
          }

          DebugState.Pause
        }
      }

      object InfoDiff extends DebugCommand with DebugCommandValidateZeroArgs {
        val name = "diff"
        override lazy val short = "diff"
        val desc = "display the differences from the previous state"
        val longDesc = desc
        def act(args: Seq[String], prestate: StateForDebugger, state: ParseOrUnparseState, processor: Processor): DebugState.Type = {
          debugPrintln("%s:".format(name))
          var diff = false
          (prestate, state) match {
            case (prestate: StateForDebugger, state: ParseOrUnparseState) => {
              if (prestate.bytePos != state.bytePos) { debugPrintln("position (bytes): %d -> %d".format(prestate.bytePos, state.bytePos), "  "); diff = true }
              if (prestate.bitLimit0b != state.bitLimit0b) { debugPrintln("bitLimit: %d -> %d".format(prestate.bitLimit0b, state.bitLimit0b), "  "); diff = true }
              if (prestate.arrayPos != state.arrayPos) { debugPrintln("arrayIndex: %d -> %d".format(prestate.arrayPos, state.arrayPos), "  "); diff = true }
              if (prestate.groupPos != state.groupPos) { debugPrintln("groupIndex: %d -> %d".format(prestate.groupPos, state.groupPos), "  "); diff = true }
              if (prestate.childPos != state.childPos) { debugPrintln("childIndex: %d -> %d".format(prestate.childPos, state.childPos), "  "); diff = true }
            }
            case _ => // ok
          }
          (prestate, state) match {
            case (prestate: StateForDebugger, state: PState) => {
              if (prestate.discriminator != state.discriminator) { debugPrintln("discriminator: %s -> %s".format(prestate.discriminator, state.discriminator), "  "); diff = true }
            }
            case (prestate: StateForDebugger, state: UState) => {
              // nothing yet that is specific to Unparser
            }
            case _ => Assert.impossibleCase()
          }
          if (diff == false) {
            debugPrintln("No differences", "  ")
          }

          DebugState.Pause
        }
      }

      object InfoDiscriminator extends DebugCommand with DebugCommandValidateZeroArgs {
        val name = "discriminator"
        override lazy val short = "dis"
        val desc = "display whether or not a discriminator is set"
        val longDesc = desc
        def act(args: Seq[String], prestate: StateForDebugger, state: ParseOrUnparseState, processor: Processor): DebugState.Type = {
          state match {
            case state: PState => debugPrintln("%s: %b".format(name, state.discriminator))
            case _ => debugPrintln("%s: info only available for parse steps".format(name))
          }
          DebugState.Pause
        }
      }

      object InfoDisplays extends DebugCommand with DebugCommandValidateZeroArgs {
        val name = "displays"
        override lazy val short = "di"
        val desc = "display the current 'display' expressions"
        val longDesc = desc
        def act(args: Seq[String], prestate: StateForDebugger, state: ParseOrUnparseState, processor: Processor): DebugState.Type = {
          if (DebuggerConfig.displays.size == 0) {
            debugPrintln("%s: no displays set".format(name))
          } else {
            debugPrintln("%s:".format(name))
            DebuggerConfig.displays.foreach { d =>
              {
                val enabledStr = if (d.enabled) "" else "*"
                debugPrintln("%s%s: %s".format(d.id, enabledStr, d.cmd.mkString(" ")), "  ")
              }
            }
          }
          DebugState.Pause
        }
      }

      object InfoFoundDelimiter extends DebugCommand with DebugCommandValidateZeroArgs {
        val name = "foundDelimiter"
        override lazy val short = "fd"
        val desc = "display the current found delimiter"
        val longDesc = desc
        def act(args: Seq[String], prestate: StateForDebugger, state: ParseOrUnparseState, processor: Processor): DebugState.Type = {
          state match {
            case pstate: PState => {
              if (pstate.delimitedParseResult.isDefined) {
                val pr = pstate.delimitedParseResult.get
                debugPrintln("%s:".format(name))
                debugPrintln("foundField: %s".format(Misc.remapStringToVisibleGlyphs(pr.field.get)), "  ")
                debugPrintln("foundDelimiter: %s".format(Misc.remapStringToVisibleGlyphs(pr.matchedDelimiterValue.get)), "  ")
              } else {
                debugPrintln("%s: nothing found".format(name))
              }
            }
            case ustate: UState => {
              // TODO
            }
            case _ => Assert.impossibleCase()
          }
          DebugState.Pause
        }
      }

      object InfoGroupIndex extends DebugCommand with DebugCommandValidateZeroArgs {
        val name = "groupIndex"
        override lazy val short = "gi"
        val desc = "display the current group index"
        val longDesc = desc
        def act(args: Seq[String], prestate: StateForDebugger, state: ParseOrUnparseState, processor: Processor): DebugState.Type = {
          if (state.groupPos != -1) {
            debugPrintln("%s: %d".format(name, state.groupPos))
          } else {
            debugPrintln("%s: not in a group".format(name))
          }
          DebugState.Pause
        }
      }

      object InfoInfoset extends DebugCommand with DebugCommandValidateZeroArgs {
        val name = "infoset"
        val desc = "display the current infoset"
        val longDesc = desc

        def getInfoset(currentNode: InfosetItem) = {
          val rootNode =
            if (currentNode.isInstanceOf[InfosetElement]) {

              var tmpNode = currentNode.asInstanceOf[DIElement]
              var parent = tmpNode.diParent
              while (parent ne null) {
                tmpNode = parent
                parent = tmpNode.diParent
              }
              tmpNode
            } else {
              currentNode
            }
          rootNode.asInstanceOf[DIDocument]
        }

        def act(args: Seq[String], prestate: StateForDebugger, state: ParseOrUnparseState, processor: Processor): DebugState.Type = {
          debugPrintln("%s:".format(name))

          val infoset = getInfoset(state.infoset)
          if (infoset.getRootElement != null) {
            val sw = new java.io.StringWriter()
            val xml = new XMLTextInfosetOutputter(sw)
            infoset.visit(xml, DebuggerConfig.removeHidden)

            val infosetString = sw.toString()
            val lines = infosetString.split("\n")
            val tailLines =
              if (DebuggerConfig.infosetLines > 0) {
                val dropCount = lines.size - DebuggerConfig.infosetLines
                if (dropCount > 0) {
                  debugPrintln("...", "  ")
                }
                lines.drop(dropCount)
              } else {
                lines
              }
            tailLines.foreach(l => debugPrintln(l, "  "))
          } else {
            debugPrintln("No Infoset", "  ")
          }

          DebugState.Pause
        }
      }

      object InfoOccursBounds extends DebugCommand with DebugCommandValidateZeroArgs {
        val name = "occursBounds"
        override lazy val short = "ob"
        val desc = "display the current occurs bounds"
        val longDesc = desc
        def act(args: Seq[String], prestate: StateForDebugger, state: ParseOrUnparseState, processor: Processor): DebugState.Type = {
          if (state.occursBoundsStack.isEmpty) debugPrintln("%s: occurs bounds not set".format(name))
          else debugPrintln("%s: %d".format(name, state.occursBoundsStack.top))
          DebugState.Pause
        }
      }

      abstract class InfoProcessorBase extends DebugCommand with DebugCommandValidateZeroArgs {
        val desc = "display the current Daffodil " + name
        val longDesc = desc
        def act(args: Seq[String], prestate: StateForDebugger, state: ParseOrUnparseState, processor: Processor): DebugState.Type = {
          debugPrintln("%s: %s".format(name, processor.toBriefXML(2))) // only 2 levels of output, please!
          DebugState.Pause
        }
      }

      object InfoParser extends {
        override val name = "parser" // scala -xcheckinit reported this was uninitialized
      } with InfoProcessorBase

      object InfoUnparser extends {
        override val name = "unparser"
      } with InfoProcessorBase

      object InfoPath extends DebugCommand with DebugCommandValidateZeroArgs {
        val name = "path"
        override lazy val short = "path"
        val desc = "display the current schema component designator/path"
        val longDesc = desc
        def act(args: Seq[String], prestate: StateForDebugger, state: ParseOrUnparseState, processor: Processor): DebugState.Type = {
          debugPrintln("%s: %s".format(name, processor.context.path))
          DebugState.Pause
        }
      }
    }

    object Quit extends DebugCommand with DebugCommandValidateZeroArgs {
      val name = "quit"
      val desc = "immediately abort all processing"
      val longDesc = """|Usage: q[uit]
                        |
                        |Immediately abort all processing.""".stripMargin
      override lazy val short = "q"
      def act(args: Seq[String], prestate: StateForDebugger, state: ParseOrUnparseState, processor: Processor): DebugState.Type = {
        sys.exit(1)
      }
    }

    object Set extends DebugCommand with DebugCommandValidateSubcommands {
      val name = "set"
      val desc = "modify debugger configuration"
      val longDesc = """|Usage: set <setting> <value>
                        |
                        |Change a debugger setting, the list of settings are below.
                        |
                        |Example: set breakOnlyOnCreation false
                        |         set dataLength 100""".stripMargin
      override val subcommands = Seq(SetBreakOnFailure, SetBreakOnlyOnCreation, SetDataLength, SetInfosetLines, SetRemoveHidden, SetRepresentation, SetWrapLength)
      override lazy val short = "set"

      def act(args: Seq[String], prestate: StateForDebugger, state: ParseOrUnparseState, processor: Processor): DebugState.Type = {
        val subcmd = args.head
        val subcmdArgs = args.tail
        subcommands.find(_ == subcmd).get.act(subcmdArgs, prestate, state, processor)
        DebugState.Pause
      }

      object SetBreakOnlyOnCreation extends DebugCommand with DebugCommandValidateBoolean {
        val name = "breakOnlyOnCreation"
        val desc = "whether or not breakpoints should occur only on element creation, or always (default: true)"
        val longDesc = """|Usage: set breakOnlyOnCreation|booc <value>
                          |
                          |Set whether or not breakpoints should only be evaluated on element creation.
                          |<value> must be either true/false or 1/0. If true, breakpoints only stop on
                          |element creation. If false, breakpoints stop anytime a parser interacts with
                          |an element. Defaults to true.
                          |
                          |Example: set breakOnlyOnCreation false""".stripMargin
        override lazy val short = "booc"

        def act(args: Seq[String], prestate: StateForDebugger, state: ParseOrUnparseState, processor: Processor): DebugState.Type = {
          val state = args.head
          DebuggerConfig.breakOnlyOnCreation =
            if (state == "true" || state == "1") {
              true
            } else {
              false
            }
          DebugState.Pause
        }
      }

      object SetBreakOnFailure extends DebugCommand with DebugCommandValidateBoolean {
        val name = "breakOnFailure"
        val desc = "whether or not the debugger should break on failures (default: false)"
        val longDesc = """|Usage: set breakOnFailure|bof <value>
                          |
                          |Set whether or not the debugger should break on failures. If set to false
                          |the normal processing occurs. If set to true, any errors cause a break.
                          |Note that due to the backtracking behavior, not all failures are fatal.
                          |Defaults to false.
                          |
                          |Example: set breakOnFailure true""".stripMargin
        override lazy val short = "bof"

        def act(args: Seq[String], prestate: StateForDebugger, state: ParseOrUnparseState, processor: Processor): DebugState.Type = {
          val state = args.head
          DebuggerConfig.breakOnFailure =
            if (state == "true" || state == "1") {
              true
            } else {
              false
            }
          DebugState.Pause
        }
      }

      object SetDataLength extends DebugCommand with DebugCommandValidateInt {
        val name = "dataLength"
        val desc = "set the maximum number of bytes of the data to display. If negative, display all input data (default: 70)"
        val longDesc = """|Usage: set dataLength|dl <value>
                          |
                          |Set the number of bytes to display when displaying input data. If
                          |negative, display all input data. This only affects the 'info data'
                          |command. Defaults to 70 bytes.
                          |
                          |Example: set dataLength 100
                          |         set dataLength -1""".stripMargin
        override lazy val short = "dl"

        def act(args: Seq[String], prestate: StateForDebugger, state: ParseOrUnparseState, processor: Processor): DebugState.Type = {
          DebuggerConfig.dataLength = args.head.toInt
          DebugState.Pause
        }
      }

      object SetInfosetLines extends DebugCommand with DebugCommandValidateInt {
        val name = "infosetLines"
        val desc = "set the maximum number of lines of the infoset to display (default: -1)"
        val longDesc = """|Usage: set infosetLines|il <value>
                          |
                          |Set the maximum number of lines to display when displaying the infoset.
                          |This only affects the 'info infoset' command. This shows the last
                          |<value> lines of the infoset. If <value> is less than or equal to zero,
                          |the entire infoset is printed. Defaults to -1.
                          |
                          |Example: set infosetLines 25""".stripMargin
        override lazy val short = "il"

        def act(args: Seq[String], prestate: StateForDebugger, state: ParseOrUnparseState, processor: Processor): DebugState.Type = {
          DebuggerConfig.infosetLines = args.head.toInt
          DebugState.Pause
        }
      }

      object SetRemoveHidden extends DebugCommand with DebugCommandValidateBoolean {
        val name = "removeHidden"
        val desc = "set whether or not to remove Daffodil internal attributes when displaying the infoset (default: false)"
        val longDesc = """|Usage: set removeHidden|rh <value>
                          |
                          |Set whether or not hidden elements (e.g through the use of
                          |dfdl:hiddenGroupRef) should be displayed. This effects the 'eval' and
                          |'info infoset' commands. <value> must be either true/false or 1/0.
                          |Defaults to false.
                          |
                          |Example: set removeHidden true""".stripMargin
        override lazy val short = "rh"

        def act(args: Seq[String], prestate: StateForDebugger, state: ParseOrUnparseState, processor: Processor): DebugState.Type = {
          val state = args.head
          DebuggerConfig.removeHidden =
            if (state == "true" || state == "1") {
              true
            } else {
              false
            }
          DebugState.Pause
        }
      }

      object SetRepresentation extends DebugCommand {
        val name = "representation"
        val desc = "set the output when displaying data (default: text)"
        val longDesc = """|Usage: set representation|rp <value>
                          |
                          |Set the output when displaying data. <value> must be either
                          |'text' or 'binary'. Defaults to 'text'.
                          |Defaults to false.
                          |
                          |Example: set representation binary""".stripMargin
        override lazy val short = "rp"

        override def validate(args: Seq[String]) {
          if (args.size != 1) {
            throw new DebugException("a single argument is required")
          } else {
            args.head.toLowerCase match {
              case "text" =>
              case "binary" =>
              case _ => throw new DebugException("argument must be either 'text' or 'binary'")
            }
          }
        }

        def act(args: Seq[String], prestate: StateForDebugger, state: ParseOrUnparseState, processor: Processor): DebugState.Type = {
          DebuggerConfig.representation = args.head.toLowerCase match {
            case "text" => Representation.Text
            case "binary" => Representation.Binary
          }
          DebugState.Pause
        }
      }

      object SetWrapLength extends DebugCommand with DebugCommandValidateInt {
        val name = "wrapLength"
        val desc = "set the maximum number of bytes to display before wrapping (default: 80)"
        val longDesc = """|Usage: set wrapLength|wl <value>
                          |
                          |Set the number of characters at which point output wraps. This only
                          |affects the 'info data' and 'info infoset' commands. A length less
                          |than or equal to zero disables wrapping. Defaults to 80 characters.
                          |
                          |Example: set wrapLength 100
                          |         set wrapLength -1""".stripMargin
        override lazy val short = "wl"

        def act(args: Seq[String], prestate: StateForDebugger, state: ParseOrUnparseState, processor: Processor): DebugState.Type = {
          DebuggerConfig.wrapLength = args.head.toInt
          DebugState.Pause
        }
      }
    }

    object Step extends DebugCommand with DebugCommandValidateZeroArgs {
      val name = "step"
      val desc = "execute a single parser step"
      val longDesc = """|Usage: s[tep]
                        |
                        |Perform a single parse action, pause parsing, and display a debugger
                        |prompt.""".stripMargin
      def act(args: Seq[String], prestate: StateForDebugger, state: ParseOrUnparseState, processor: Processor): DebugState.Type = {
        DebugState.Step
      }
    }

    object Trace extends DebugCommand with DebugCommandValidateZeroArgs {
      val name = "trace"
      val desc = "same as continue, but runs display commands during every step"
      val longDesc = """|Usage: t[race]
                        |
                        |Continue parsing the input data until a breakpoint is encountered,
                        |while running display commands after every parse step. When a
                        |breakpoint is encountered, pause parsing and display a debugger
                        |console to the user.""".stripMargin
      def act(args: Seq[String], prestate: StateForDebugger, state: ParseOrUnparseState, processor: Processor): DebugState.Type = {
        DebugState.Trace
      }
    }
  }
}
