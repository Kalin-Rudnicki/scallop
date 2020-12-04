package org.rogach.scallop

import org.rogach.scallop.exceptions._

import scala.collection.{Seq => CSeq}

/** The creator and god of all parsers :) */
private[scallop] object Scallop {

  /** Create the new parser with some arguments already inserted.
    *
    * @param args Args to pre-insert.
    */
  def apply(args: CSeq[String]): Scallop = new Scallop(args)

  /** Create the default empty parser, fresh as mountain air. */
  def apply(): Scallop = apply(Nil)

  private[scallop] def builtinHelpOpt =
    SimpleOption(
      name = "help",
      short = None,
      descr = "Show help message",
      required = false,
      converter = flagConverter,
      default = () => None,
      validator = (_) => true,
      argName = "",
      hidden = false,
      noshort = true
    )

  private[scallop] def builtinVersionOpt =
    SimpleOption(
      name = "version",
      short = None,
      descr = "Show version of this program",
      required = false,
      converter = flagConverter,
      default = () => None,
      validator = (_) => true,
      argName = "",
      hidden = false,
      noshort = true
    )
}

/** The main builder class.
  *
  * @param args Arguments to parse.
  * @param opts Options definitions.
  * @param mainOpts Names of options, that are to be printed first in the help printout
  * @param vers Version string to display in help.
  * @param bann Banner (summary of this program and command-line usage) to display in help.
  * @param foot Footer - displayed after options.
  * @param descr Short description - used for subcommands
  * @param helpWidth Width, to which the help output will be formatted (note that banner, footer, version and description are not affected!)
  * @param shortSubcommandsHelp If true, then help output from this builder wouldn't list full help for subcommands, only short description
  * @param appendDefaultToDescription If true, then append auto-generated text about default option value to option descriptions
  * @param noshort If true, then do not generate short names for options by default unless overridden per option by providing its noshort parameter
  * @param helpFormatter help formatter in this builder
  * @param subbuilders subcommands in this builder
  */
case class Scallop(
  args: CSeq[String] = Nil,
  opts: List[CliOption] = Nil,
  mainOpts: List[String] = Nil,
  vers: Option[String] = None,
  bann: Option[String] = None,
  foot: Option[String] = None,
  descr: String = "",
  helpWidth: Option[Int] = None,
  shortSubcommandsHelp: Boolean = false,
  appendDefaultToDescription: Boolean = false,
  noshort: Boolean = false,
  helpFormatter: ScallopHelpFormatter = new ScallopHelpFormatter,
  subbuilders: List[(String, Scallop)] = Nil
) extends ScallopArgListLoader {

  var parent: Option[Scallop] = None

  type Parsed = List[(CliOption, (String, List[String]))]

  case class ParseResult(
    opts: Parsed = Nil,
    subcommand: Option[String] = None,
    subcommandArgs: List[String] = Nil
  )

  /** Parse the argument into list of options and their arguments. */
  private def parse(args: CSeq[String]): ParseResult = {
    subbuilders.filter(s => args.contains(s._1)).sortBy(s => args.indexOf(s._1)).headOption match {
      case Some((name, sub)) => ParseResult(parse(Nil, args.takeWhile(name != _).toList), Some(name), args.dropWhile(name != _).drop(1).toList)
      case None => ParseResult(parse(Nil, args.toList))
    }
  }
  @annotation.tailrec
  private def parse(acc: Parsed, args: List[String]): Parsed = {
    def goParseRest(args: List[String], opt: Option[(String, CliOption)]): Parsed = {
      def parseRest = {
        val trailingOptions =
          opt.map(o => (o._2, o._1, true)).toList :::
          opts.filter(_.isPositional).map(o => (o, "", o.required))
        val trailingConverters = trailingOptions.map {
          case (opt, invocation, required) => (opt.converter, invocation, required)
        }

        val res = TrailingArgumentsParser.parse(args.toList, trailingConverters)
        res match {
          case TrailingArgumentsParser.ParseResult(_, _, excess) if excess.nonEmpty =>
            throw ExcessArguments(excess)

          case TrailingArgumentsParser.ParseResult(result, _, _) if result.exists(_.isLeft) =>
            trailingOptions.zip(result).find(_._2.isLeft).get match {
              case ((option, _, _), Left((message, args))) =>
                if (option.required && (message == "not enough arguments"))
                  throw RequiredOptionNotFound(option.name)
                else
                  throw WrongOptionFormat(option.name, args.mkString(" "), message)
              case _ => throw MajorInternalException()
            }

          case TrailingArgumentsParser.ParseResult(result, _, _) =>
            trailingOptions.zip(result).flatMap {
              case ((option, invocation, required), Right(args)) =>
                if (args.nonEmpty || required) {
                  List((option, (invocation, args)))
                } else Nil
              case _ => throw MajorInternalException()
            }
        }
      }

      opt match {
        case Some((invoc, o)) =>
          // short-circuit parsing when there are no trailing args - to get better error messages
          o.converter.argType match {
            case ArgType.FLAG   =>
              (o, (invoc, Nil)) :: goParseRest(args, None)
            case ArgType.SINGLE =>
              if (args.size > 0) {
                (o, (invoc, args.take(1).toList)) :: goParseRest(args.tail, None)
              } else {
                throw new WrongOptionFormat(o.name, args.mkString, "you should provide exactly one argument")
              }
            case ArgType.LIST if args.isEmpty => List(o -> ((invoc, Nil)))
            case ArgType.LIST => parseRest
          }
        case None => parseRest
      }
    }

    if (args.isEmpty) acc.reverse
    else if (isOptionName(args.head) && args.head != "--") {
      if (args.head.startsWith("--")) {
        opts.find(_.longNames.exists(name => args.head.startsWith("--" + name + "="))) match {

          // pase --arg=value option style
          case Some(opt) =>
            val (invocation, arg) = args.head.drop(2).span('=' != _)
            parse(acc = (opt, (invocation, List(arg.drop(1)))) :: acc, args = args.tail)

          // parse usual --arg value... option style
          case None =>
            val optName = args.head.drop(2)
            val opt =
              opts.find(_.longNames.contains(optName))
              .orElse(if (optName == "help") Some(getHelpOption) else None)
              .orElse(if (optName == "version") getVersionOption else None)
              .getOrElse(throw new UnknownOption(optName))
            val (before, after) = args.tail.span(isArgument)
            if (after.isEmpty) {
              // get the converter, proceed to trailing args parsing
              acc.reverse ::: goParseRest(args.tail, Some((args.head.drop(2),opt)))
            } else {
              parse( acc = (opt -> ((args.head.drop(2), before.toList))) :: acc,
                     args = after)
            }
        }
      } else {
        if (args.head.size == 2) {
          val opt = getOptionWithShortName(args.head(1)) getOrElse
                    (throw new UnknownOption(args.head.drop(1)))
          val (before, after) = args.tail.span(isArgument)
          if (after.isEmpty) {
            // get the converter, proceed to trailing args parsing
            acc.reverse ::: goParseRest(args.tail, Some((args.head.drop(1), opt)))
          } else {
            parse( acc = (opt -> ((args.head.drop(1), before.toList))) :: acc,
                   args = after)
          }
        } else {
          val opt = getOptionWithShortName(args.head(1)) getOrElse
                    (throw new UnknownOption(args.head(1).toString))
          if (opt.converter.argType != ArgType.FLAG) {
            parse(acc, args.head.take(2) :: args.head.drop(2) :: args.tail)
          } else {
            parse(acc, args.head.take(2) :: ("-" + args.head.drop(2)) :: args.tail)
          }
        }
      }
    } else if (args.head.matches("-[0-9]+")) {
      // parse number-only options
      val alreadyMatchedNumbers = acc.count(_._1.isInstanceOf[NumberArgOption])
      opts.filter(_.isInstanceOf[NumberArgOption]).drop(alreadyMatchedNumbers).headOption match {
        case Some(opt) =>
          val num = args.head.drop(1)
          parse(acc = (opt, (num, List(num))) :: acc, args = args.tail)
        case None =>
          // only trailing args are left - proceed to trailing args parsing
          acc.reverse ::: goParseRest(args, None)
      }
    } else {
      // only trailing args left - proceed to trailing args parsing
      val trailArgs = if (args.head == "--") args.tail else args
      acc.reverse ::: goParseRest(trailArgs, None)
    }
  }

  /** Find an option, that responds to this short name. */
  def getOptionWithShortName(c: Char): Option[CliOption] = {
    opts
    .find(_.requiredShortNames.contains(c))
    .orElse {
      opts.find(_.shortNames.contains(c))
    }
    .orElse(Option(getHelpOption).find(_.requiredShortNames.contains(c)))
    .orElse(getVersionOption.find(_.requiredShortNames.contains(c)))
  }

  def getOptionShortNames(opt: CliOption): List[Char] = {
    (opt.shortNames ++ opt.requiredShortNames).distinct
    .filter(sh => getOptionWithShortName(sh).get == opt)
  }

  /** Result of parsing */
  private lazy val parsed: ParseResult = parse(loadArgList(args))

  /** Tests whether this string contains option name, not some number. */
  private def isOptionName(s: String) =
    if (s.startsWith("-"))
      if (s.size > 1)
        !s(1).isDigit
      else if (s.size == 1)
        false
      else true
    else false

  /** Tests whether this string contains option parameter, not option call. */
  private def isArgument(s: String) = !isOptionName(s)


  /** Add a new option definition to this builder.
    *
    * @param name Name for new option, used as long option name in parsing, and for option identification.
    * @param short Overload the char that will be used as short option name.
                   Defaults to first character of the name.
    * @param descr Description for this option, for help description.
    * @param default Default value to use if option is not found in input arguments
                     (if you provide this, you can omit the type on method).
    * @param required Is this option required? Defaults to false.
    * @param argName The name for this ortion argument, as it will appear in help. Defaults to "arg".
    * @param noshort If set to true, then this option does not have any short name.
    * @param conv The converter for this option. Usually found implicitly.
    * @param validate The function, that validates the parsed value
    * @param hidden Hides description of this option from help (this can be useful for debugging options)
    */
  def opt[A](
      name: String,
      short: Char = '\u0000',
      descr: String = "",
      default: () => Option[A] = () => None,
      validate: A => Boolean = ((_:A) => true),
      required: Boolean = false,
      argName: String = "arg",
      hidden: Boolean = false,
      noshort: Boolean = noshort)
      (implicit conv: ValueConverter[A]): Scallop = {
    if (name.head.isDigit) throw new IllegalOptionParameters(Util.format("First character of the option name must not be a digit: %s", name))
    val defaultA =
      if (conv == flagConverter)
        { () =>
          if (default() == Some(true)) Some(true)
          else Some(false)
        }
      else default
    val eShort = if (short == '\u0000' || noshort) None else Some(short)
    val validator = { (a:Any) => validate(a.asInstanceOf[A]) }
    this.copy(opts = opts :+ SimpleOption(name,
                                          eShort,
                                          descr,
                                          required,
                                          conv,
                                          defaultA,
                                          validator,
                                          argName,
                                          hidden,
                                          noshort))
  }

  /** Add new property option definition to this builder.
    *
    * @param name Char, that will be used as prefix for property arguments.
    * @param descr Description for this property option, for help description.
    * @param keyName Name for 'key' part of this option arg name, as it will appear in help option definition. Defaults to "key".
    * @param valueName Name for 'value' part of this option arg name, as it will appear in help option definition. Defaults to "value".
    */
  def props[A](
      name: Char,
      descr: String = "",
      keyName: String = "key",
      valueName: String = "value",
      hidden: Boolean = false)
      (implicit conv: ValueConverter[Map[String,A]]): Scallop =
    this.copy(opts = opts :+ PropertyOption(name.toString, name, descr, conv, keyName, valueName, hidden))

  def propsLong[A](
      name: String,
      descr: String = "",
      keyName: String = "key",
      valueName: String = "value",
      hidden: Boolean = false)
      (implicit conv: ValueConverter[Map[String,A]]): Scallop =
    this.copy(opts = opts :+ LongNamedPropertyOption(name,
                                                     descr,
                                                     conv,
                                                     keyName,
                                                     valueName,
                                                     hidden))

  /** Add new trailing argument definition to this builder.
    *
    * @param name Name for new definition, used for identification.
    * @param required Is this trailing argument required? Defaults to true.
    * @param descr Description for this option, for help text.
    * @param default If this argument is not required and not found in the argument list, use this value.
    * @param validate The function, that validates the parsed value
    */
  def trailArg[A](
      name: String,
      required: Boolean = true,
      descr: String = "",
      default: () => Option[A] = () => None,
      validate: A => Boolean = ((_:A) => true),
      hidden: Boolean = false)
      (implicit conv: ValueConverter[A]): Scallop = {
    val defaultA =
      if (conv == flagConverter)
        { () =>
          if (default() == Some(true)) Some(true)
          else Some(false)
        }
      else default
    val validator = { (a:Any) => validate(a.asInstanceOf[A]) }
    this.copy(opts = opts :+ TrailingArgsOption(name,
                                                required,
                                                descr,
                                                conv,
                                                validator,
                                                defaultA,
                                                hidden))
  }

  /** Add new number argument definition to this builder.
    *
    * @param name Name for new definition, used for identification.
    * @param required Is this trailing argument required? Defaults to true.
    * @param descr Description for this option, for help text.
    * @param default If this argument is not required and not found in the argument list, use this value.
    * @param validate The function that validates the parsed value.
    * @param hidden If set to true then this option will not be present in auto-generated help.
    */
  def number(
      name: String,
      required: Boolean = false,
      descr: String = "",
      default: () => Option[Long] = () => None,
      validate: Long => Boolean = ((_:Long) => true),
      hidden: Boolean = false)
      (implicit conv: ValueConverter[Long]): Scallop = {

    val validator = { (a: Any) => validate(a.asInstanceOf[Long]) }
    this.copy(opts = opts :+ NumberArgOption(
      name,
      required,
      descr,
      conv,
      validator,
      default,
      hidden
    ))
  }

  /** Add new toggle option definition to this builer.
    *
    * @param name  Name for new definition, used for identification.
    * @param default Default value
    * @param short Name for short form of this option
    * @param noshort If set to true, then this option will not have any short name.
    * @param prefix Prefix to name of the option, that will be used for "negative" version of the
                    option.
    * @param descrYes Description for positive variant of this option.
    * @param descrNo Description for negative variant of this option.
    * @param hidden If set to true, then this option will not be present in auto-generated help.
    */
  def toggle(
      name: String,
      default: () => Option[Boolean] = () => None,
      short: Char = '\u0000',
      noshort: Boolean = noshort,
      prefix: String = "no",
      descrYes: String = "",
      descrNo: String = "",
      hidden: Boolean = false) = {
    val eShort = if (short == '\u0000' || noshort) None else Some(short)
    this.copy(opts = opts :+ ToggleOption(name,
                                          default,
                                          eShort,
                                          noshort,
                                          prefix,
                                          descrYes,
                                          descrNo,
                                          hidden))
  }

  /** Adds a subbuilder (subcommand) to this builder.
    * @param name All arguments after this string would be routed to this builder.
    */
  def addSubBuilder(nameAndAliases: Seq[String], builder: Scallop) = {
    builder.parent = Some(this)
    this.copy(subbuilders = subbuilders ++ nameAndAliases.map(name => name -> builder))
  }

  /** Traverses the tree of subbuilders, using the provided name.
    * @param name Names of subcommand names, that lead to the needed builder, separated by \\0.
    */
  def findSubbuilder(name: String): Option[Scallop] = {
    if (name.contains('\u0000')) {
      val (firstSub, rest) = name.span('\u0000' != _)
      subbuilders.find(_._1 == firstSub).flatMap(_._2.findSubbuilder(rest.tail))
    } else subbuilders.find(_._1 == name).map(_._2)
  }

  /** Retrieves name of the subcommand that was found in input arguments. */
  def getSubcommandName = parsed.subcommand

  /** Retrieves the subbuilder object,
    * that matches the name of the subcommand found in input arguments. */
  def getSubbuilder: Option[Scallop] = parsed.subcommand.flatMap { sn =>
    subbuilders.find(_._1 == sn).map(_._2)
  }

  /** Returns the subcommand arguments. */
  def getSubcommandArgs: List[String] = parsed.subcommandArgs

  /** Returns the list of subcommand names, recursively. */
  def getSubcommandNames: List[String] = {
    parsed.subcommand.map(subName => subbuilders.find(_._1 == subName).map(s => s._1 :: s._2.args(parsed.subcommandArgs).getSubcommandNames).getOrElse(Nil)).getOrElse(Nil)
  }

  /** Retrieves a list of all supplied options (including options from subbuilders). */
  def getAllSuppliedOptionNames: List[String] = {
    opts.map(_.name).filter(isSupplied) ::: parsed.subcommand.map(subName => subbuilders.find(_._1 == subName).map(s => s._2.args(parsed.subcommandArgs)).get.getAllSuppliedOptionNames.map(subName + "\u0000" + _)).getOrElse(Nil)
  }

  /** Add version string to this builder.
    *
    * @param v Version string, to be printed before all other things in help.
    */
  def version(v: String) = this.copy(vers = Some(v))

  /** Add banner string to this builder. Banner should describe your program and provide a short
    * summary on it's usage.
    *
    * @param b Banner string, can contain multiple lines. Note this is not formatted to 80 characters!
    */
  def banner(b: String) = this.copy(bann = Some(b))

  /** Add footer string to this builder. Footer will be printed in help after option definitions.
    *
    * @param f Footer string, can contain multiple lines. Note this is not formatted to 80 characters!
    */
  def footer(f: String) = this.copy(foot = Some(f))

  /** Explicitly sets the needed width for the help printout. */
  def setHelpWidth(w: Int) = this.copy(helpWidth = Some(w))

  /** Get help on options from this builder. The resulting help is carefully formatted to required number of columns (default = 80, change with .setHelpWidth method),
    * and contains info on properties, options and trailing arguments.
    */
  def help: String = helpFormatter.formatHelp(this, "")

  /** Print help message (with version, banner, option usage and footer) to stdout. */
  def printHelp() = {
    vers foreach println
    bann foreach println
    println(help)
    foot foreach println
  }

  /** Add some more arguments to this builder. They are appended to the end of the original list.
    *
    * @param a arg list to add
    */
  def args(a: Seq[String]): Scallop = this.copy(args = args ++ a)

  /** Tests if this option or trailing arg was explicitly provided by argument list (not from default).
    *
    * @param name Identifier of option or trailing arg definition
    */
  def isSupplied(name: String): Boolean = {
    if (name.contains('\u0000')) {
      // delegating to subbuilder
      parsed.subcommand.map { subc =>
        subbuilders
        .find(_._1 == subc).map(_._2)
        .filter { subBuilder =>
          subbuilders.filter(_._2 == subBuilder)
          .exists(_._1 == name.takeWhile('\u0000' != _))
        }
        .map { subBuilder =>
          subBuilder.args(parsed.subcommandArgs).isSupplied(name.dropWhile('\u0000'!=).drop(1))
        }.getOrElse(false) // only current subcommand can have supplied arguments
      }.getOrElse(false) // no subcommands, so their options are definitely not supplied
    } else {
      opts find (_.name == name) map { opt =>
        val args = parsed.opts.filter(_._1 == opt).map(_._2)
        opt.converter.parseCached(args) match {
          case Right(Some(_)) => true
          case _ => false
        }
      } getOrElse(throw new UnknownOption(name))
    }
  }

   /** Get the value of option (or trailing arg) as Option.
     * @param name Name for option.
     */
  def get(name: String): Option[Any] = {
    if (name.contains('\u0000')) {
      // delegating to subbuilder
      subbuilders.find(_._1 == name.takeWhile('\u0000'!=)).map(_._2.args(parsed.subcommandArgs).get(name.dropWhile('\u0000'!=).drop(1)))
        .getOrElse(throw new UnknownOption(name.replace("\u0000",".")))
    } else {
      opts.find(_.name == name).map { opt =>
        val args = parsed.opts.filter(_._1 == opt).map(_._2)
        opt.converter.parseCached(args) match {
          case Right(parseResult) =>
            parseResult.orElse(opt.default())
          case _ => if (opt.required) throw new MajorInternalException else None
        }
      }.getOrElse(throw new UnknownOption(name))
    }
  }

  def get(name: Char): Option[Any] = get(name.toString)

  /** Get the value of option. If option is not found, this will throw an exception.
    *
    * @param name Name for option.
    */
  def apply(name: String): Any = get(name).get

  def apply(name: Char): Any = apply(name.toString)

  def prop(name: Char, key: String): Option[Any] = apply(name).asInstanceOf[Map[String, Any]].get(key)

  lazy val getHelpOption =
    opts.find(_.name == "help")
    .getOrElse(
      if (opts.exists(opt => getOptionShortNames(opt).contains('h'))) {
        Scallop.builtinHelpOpt
      } else {
        Scallop.builtinHelpOpt.copy(short = Some('h'), noshort = false)
      }
    )

  lazy val getVersionOption =
    vers.map(_ => opts.find(_.name == "version")
    .getOrElse(
      if (opts.exists(opt => getOptionShortNames(opt).contains('v'))) {
        Scallop.builtinVersionOpt
      } else {
        Scallop.builtinVersionOpt.copy(short = Some('v'), noshort = false)
      }
    ))

  /** Verify the builder. Parses arguments, makes sure no definitions clash, no garbage or unknown options are present,
    * and all present arguments are in proper format. It is recommended to call this method before using the results.
    *
    * If there is "--help" or "--version" option present, it prints help or version statement and exits.
    */
  def verify: Scallop = {
    // option identifiers must not clash
    opts map (_.name) groupBy (a=>a) filter (_._2.size > 1) foreach
      (a => throw new IdenticalOptionNames(Util.format("Option identifier '%s' is not unique", a._1)))
    // long options names must not clash
    opts flatMap (_.longNames) groupBy (a=>a) filter (_._2.size > 1) foreach
      (a => throw new IdenticalOptionNames(Util.format("Long option name '%s' is not unique", a._1)))
    // short options names must not clash
    opts flatMap (o => (o.requiredShortNames).distinct) groupBy (a=>a) filter (_._2.size > 1) foreach
      (a => throw new IdenticalOptionNames(Util.format("Short option name '%s' is not unique", a._1)))


    val helpOpt = getHelpOption
    val shortHelpOpt = helpOpt match {
      case o: SimpleOption => o.short
      case _ => None
    }
    if (args.headOption == Some("--" + helpOpt.name) ||
        shortHelpOpt.map(s => args.headOption == Some("-" + s)).getOrElse(false)) {
      throw Help("")
    }

    getVersionOption.foreach { versionOpt =>
      val shortVersionOpt = versionOpt match {
        case o: SimpleOption => o.short
        case _ => None
      }
      if (args.headOption == Some("--" + versionOpt.name) ||
          shortVersionOpt.map(s => args.headOption == Some("-" + s)).getOrElse(false)) {
        throw Version
      }
    }

    parsed

    // verify subcommand parsing
    parsed.subcommand.map { sn =>
      subbuilders.find(_._1 == sn).map { case (sn, sub)=>
        try {
          sub.args(parsed.subcommandArgs).verify
        } catch {
          case Help("") => throw Help(sn)
          case h @ Help(subname) => throw Help(sn + "\u0000" + subname)
        }
      }
    }

    opts foreach { o =>
      val args = parsed.opts filter (_._1 == o) map (_._2)
      val res = o.converter.parseCached(args)
      res match {
        case Left(msg) =>
          throw new WrongOptionFormat(o.name, args.map(_._2.mkString(" ")).mkString(" "), msg)
        case _ =>
      }
      if (o.required && !res.fold(_ => false, _.isDefined) && !o.default().isDefined)
        throw new RequiredOptionNotFound(o.name)
      // validaiton
      if (!(get(o.name) map (v => o.validator(v)) getOrElse true))
        throw new ValidationFailure(Util.format("Validation failure for '%s' option parameters: %s", o.name, args.map(_._2.mkString(" ")).mkString(" ")))

    }

    this
  }

  /** Get summary of current parser state.
    *
    * Returns a list of all options in the builder, and corresponding values for them.
    */
  def summary: String = {
    Util.format("Scallop(%s)", args.mkString(", ")) + "\n" + filteredSummary(Set.empty)
  }

  /** Get summary of current parser state + blurring the values of parameters provided.
    *
    * @param blurred names of arguments that should be hidden.
    *
    * Returns a list of all options in the builder, and corresponding values for them
    * with eventually blurred values.
    */
  def filteredSummary(blurred:Set[String]): String = {
    lazy val hide = "************"
    opts.map { o =>
      Util.format(
        " %s  %s => %s",
        (if (isSupplied(o.name)) "*" else " "),
        o.name,
        if(!blurred.contains(o.name)) get(o.name).getOrElse("<None>") else hide
      )
    }.mkString("\n") + "\n" + parsed.subcommand.map { sn =>
      Util.format("subcommand: %s\n", sn) + subbuilders.find(_._1 == sn).get._2.args(parsed.subcommandArgs).filteredSummary(blurred)
    }.getOrElse("")
  }

}
