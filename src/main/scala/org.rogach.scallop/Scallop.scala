package org.rogach.scallop

import scala.reflect.Manifest
import org.rogach.scallop.exceptions._

/** The creator and god of all parsers :) */
object Scallop {
  /** Create the new parser with some arguments already inserted.
    * @param args Args to pre-insert.
    */
  def apply(args:Seq[String]):Scallop = new Scallop(args,Nil,Nil,Nil,None,None)
  /** Create the default empty parser, fresh as mountain air. */
  def apply():Scallop = apply(List())

}

/** The main builder class.
  * @param args Arguments to parse.
  * @param opts Options definitions.
  * @param propts Property options definitions.
  * @param trail Definitions for trailing arguments.
  * @param vers Version string to display in help.
  * @param banner Banner (summary of this program and command-line usage) to display in help.
  */
case class Scallop(args:Seq[String], opts:List[OptDef], propts:List[PropDef], trail:List[TrailDef], vers:Option[String], bann:Option[String]) {
  /** Options and trailing arguments. */
  private lazy val (pargs,rest) = parseWithRest(args)
  
  /** Just a shortcut. */
  private type ArgParsed = (Option[String],Option[String],List[String]) // (short, long, args)
  /** Takes the whole arguments list and parses into options and trailing arguments.
    * @param args The command-line arguments.
    * @return (parsed options, a list of strings corresponding to trailing arguments one-to-one.
    */
  private def parseWithRest(args:Seq[String]):(List[ArgParsed], List[List[String]]) = {
    // the part of arguments after the last option
    val trailArgs = args.reverse.takeWhile(!_.startsWith("-")).reverse
    // name for that last option
    val optNam = args.reverse.drop(trailArgs.size).headOption 
    optNam match {
      case Some(optName) =>
        // find the proper converter
        // options are always required (if we saw it, we must match it), but property options are not
        val (optConv,required) = if (optName.startsWith("--")) {
          (opts ++ propts).find(_._name == optName.drop(2)).map {o => (o._conv, true)}
          .getOrElse(throw new UnknownOption("Unknown option '%s'" format optName.drop(2)))
        } else {
          propts.find(_.char == optName(1)).map {o => (o._conv, false)}
          .getOrElse(
            opts.map(a => (a,getOptShortName(a))).filter(_._2.isDefined).find(_._2.get == optName.last).map {o => (o._1._conv, true)}
              .getOrElse(throw new UnknownOption("Unknown option '%s'" format optName.drop(1))))
        }
        val rest = parseRest(trailArgs.toList, (optConv, required) :: trail.map(t => (t.conv, t.required))).getOrElse(throw new OptionParseException("Failed to parse the trailing argument list"))
        (parse(args.reverse.drop(trailArgs.size - rest.headOption.map(_.size).getOrElse(0)).reverse), rest.tail)
      case None => // no last option - there were only trailing arguments in input
        val rest = parseRest(trailArgs.toList, trail.map(t => (t.conv, t.required))).getOrElse(throw new OptionParseException("Failed to parse the trailing argument list"))
        (Nil, rest)
    }
  }
  /** Parses the list of arguments into pieces, according to "-" at the arguments beginngings.
    * @param args Argument list to parse.
    * @return List of tuples, that contain short (or long) option name, and list of args corresponding to it.
    */
  def parse(args:Seq[String]):List[ArgParsed] = {
    args.toList match {
      case a :: rest if a.startsWith("--") => // it starts with -- => surely a long option name
        (None,Some(a.drop(2)),rest.takeWhile(!_.startsWith("-"))) :: 
          parse(rest.dropWhile(!_.startsWith("-")))
      case a :: rest if a.startsWith("-") => // can be either property or several short options
        if (propts.find(_.char == a(1)).isDefined) {
          (Some(a(1).toString), None, (a.drop(2) +: rest.takeWhile(!_.startsWith("-"))).filter(_.size > 0)) ::
          parse(rest.dropWhile(!_.startsWith("-")))
        } else { // no, not property
          a.drop(1).init.map(i => (Some(i.toString), None, List[String]())).toList :::  // short option names, that will not have any arguments
          List((Some(a.last.toString),None,rest.takeWhile(!_.startsWith("-")))) ::: // last short option, all arguments go to it
          parse(rest.dropWhile(!_.startsWith("-")))
        }
      case Nil => List() // no arguments - no options :)
      case a => throw new OptionParseException("Failed to parse options: " + a.mkString(" "))// there should be options!
    }
  }
  /** Parses the trailing arguments (including the arguments to last option).
    * @param args arguments to parse
    * @param convs list of converters and the flags, indicating if that converter must match
    * @return None if match fails, a list of argument lists otherwise. The size of returned list is equal to size of
    *         converter list.
    */
  private def parseRest(args:List[String], convs:List[(ValueConverter[_], Boolean)]):Option[List[List[String]]] = {
    if (convs.isEmpty) {
      if (args.isEmpty) Some(Nil)
      else None // some arguments are still, and there are no converters to match them => no match
    } else {
      // the remainders of arguments, to be matched by subsequent converters
      val remainders = convs.head._1.argType match {
        case ArgType.FLAG => List(args) // all of them
        case ArgType.SINGLE => // either the full list or it's tail - we can match only one argument
          if (convs.head._2) if (args.isEmpty) List(Nil) else List(args.tail)
          else if (args.isEmpty) List(Nil) else List(args.tail, args)
        case ArgType.LIST => // if it is required, we must match at least one argument
          if (convs.head._2) args.tails.toList.reverse.init
          else args.tails.toList.reverse
      }
      remainders.view.map { rem =>
        val p = args.take(args.size - rem.size) // to be matched by current converter
        if (p.isEmpty && !convs.head._2) { // will it match an empty list?
            val next = parseRest(rem, convs.tail)
            if (next.isDefined) {
              Some(p :: next.get)
            } else None 
        } else {
          convs.head._1.parse(List(p)) match {
            case Right(a) if a.isDefined => 
              val next = parseRest(rem, convs.tail)
              if (next.isDefined) {
                Some(p :: next.get)
              } else None
            case _ => None
          }
        }
      }.find(_.isDefined).getOrElse(None)
    }
  }
  
  /** Add a new option definition to this builder.
    * @param name Name for new option, used as long option name in parsing, and for option identification.
    * @param short Overload the char that will be used as short option name. Defaults to first character of the name.
    * @param descr Description for this option, for help description.
    * @param default Default value to use if option is not found in input arguments (if you provide this, you can omit the type on method).
    * @param required Is this option required? Defaults to false.
    * @param arg The name for this ortion argument, as it will appear in help. Defaults to "arg".
    * @param conv The converter for this option. Usually found implicitly.
    */
  def opt[A](name:String, short:Char = 0.toChar, descr:String = "", default:Option[A] = None, required:Boolean = false, arg:String = "arg")(implicit conv:ValueConverter[A]):Scallop = {
    val eShort = if (short == 0.toChar) None else Some(short)
    this.copy(opts = opts :+ new OptDef(name, eShort, descr, conv, default, required, arg))
  }
  /** Add new property option definition to this builder.
    * @param name Char, that will be used as prefix for property arguments.
    * @param descr Description for this property option, for help description.
    * @param keyName Name for 'key' part of this option arg name, as it will appear in help option definition. Defaults to "key".
    * @param valueName Name for 'value' part of this option arg name, as it will appear in help option definition. Defaults to "value".
    */
  def props(name:Char,descr:String = "", keyName:String = "key", valueName:String = "value"):Scallop = {
    this.copy(propts = propts :+ new PropDef(name,descr,keyName, valueName))
  }
  /** Add new trailing argument definition to this builder.
    * @param name Name for new definition, used for identification.
    * @param required Is this trailing argument required? Defaults to true.
    */
  def trailArg[A](name:String, required:Boolean = true)(implicit conv:ValueConverter[A]):Scallop = {
    this.copy(trail = trail :+ new TrailDef(name, required, conv))
  }
  
  /** Add version string to this builder.
    * @param v Version string, to be printed before all other things in help.
    */
  def version(v:String) = this.copy(vers = Some(v))
  /** Add banner string to this builder. Banner should describe your program and provide a short
      summary on it's usage.
    * @param b Banner string, can contain multiple lines. Note this is not formatted to 80 characters!
    */
  def banner(b:String) = this.copy(bann = Some(b))
  /** Get help on options from this builder. The resulting help is carefully formatted at 80 columns,
      and contains info on proporties and options. It does not contain info about trailing arguments.
    */
  def help:String = (opts ++ propts).sortBy(_._name.toLowerCase).map{ 
    case o:OptDef => o.help(getOptShortName(o))
    case o:PropDef => o.help(Some(o.char))
  }.mkString("\n")
  /** Add some more arguments to this builder. They are appended to the end of the original list.
    * @param a arg list to add
    */
  def args(a:Seq[String]) = this.copy(args = args ++ a)
  
  /** Get the value of option (or trailing arg) as Option.
    * @param name Name for option.
    * @param m Manifest for requested type. Usually found implicitly.
    */
  def get[A](name:String)(implicit m:Manifest[A]):Option[A] = {
    opts.find(_.name == name).map { opt =>
      val sh = getOptShortName(opt)
      if (!(opt.conv.manifest <:< m)) {
        throw new WrongTypeRequest("Requested '%s' instead of '%s'" format (m, opt.conv.manifest))
      }
      opt.conv.parse(pargs.filter(a => a._2.map(opt.name ==)
      .getOrElse(sh.map(a._1.get.head == _).getOrElse(false))).map(_._3)).right.get
      .orElse(opt.default).asInstanceOf[Option[A]]
    }.getOrElse{
      trail.zipWithIndex.find(_._1.name == name).map { case (tr, idx) =>
        if (!(tr.conv.manifest <:< m)) {
          throw new WrongTypeRequest("Requested '%s' instead of '%s'" format (m, tr.conv.manifest))
        }
        tr.conv.parse(List(rest(idx))).right.getOrElse(if (tr.required) throw new MajorInternalException else None).asInstanceOf[Option[A]]
      }.getOrElse(throw new UnknownOption("Unknown option requested: '%s'" format name))
    }
  }
  /** Get the value of option. If option is not found, this will throw an exception.
    * @param name Name for option.
    * @param m Manifest for requested type. Usually found implicitly.
    */
  def apply[A](name:String)(implicit m:Manifest[A]):A = get(name)(m).get
  /** Get the value of some prorerty
    * @param name Property definition identifier.
    * @param key Name of the property to retreive.
    * @return Some(value) if property is found, None otherwise.
    */
  def prop(name:Char, key:String):Option[String] = {
    pargs.filter(_._1 == Some(name.toString)).flatMap { p =>
      val rgx = """([^=]+)=(.*)""".r
      p._3.collect {
        case rgx(key, value) => (key, value)
      }
    }.find(_._1 == key).map(_._2)
  }
  /** Get all data for propety as Map.
    * @param name Propety definition identifier.
    * @return All key-value pairs for this property in a map.
    */
  def propMap(name:Char):Map[String,String] = {
    pargs.filter(_._1 == Some(name.toString)).flatMap { p =>
      val rgx = """([^=]+)=(.*)""".r
      p._3.collect {
        case rgx(key, value) => (key, value)
      }
    }.toMap
  }

  /** Determine the short name for the option (if available). */
  private def getOptShortName(o:OptDef):Option[Char] =
    o.short.orElse {
      val sh = o.name.head
      if ((opts.map(_.short).flatten ++ propts.map(_.char)).contains(sh)) None
      else if (opts.takeWhile(o !=).filter(!_.short.isDefined).map(_.name.head).contains(sh)) None
           else Some(sh)
    }

  /** Verify the builder. Parses arguments, makes sure no definitions clash, no garbage or unknown options are present,
      and all present arguments are in proper format. It is recommended to call this method before using the results.
      
      If there is "--help" or "--version" option present, it prints help or version statement and exits.
    */
  def verify = {
    // long options must not clash
    (opts.map(_.name) ++ trail.map(_.name)).groupBy(a=>a).filter(_._2.size > 1)
    .foreach(a => throw new IdenticalOptionNames("Long option name '%s' is not unique" format a._1))
    // short options must not clash
    (opts.map(_.short).flatten ++ propts.map(_.char)).groupBy(a=>a).filter(_._2.size > 1)
    .foreach(a => throw new IdenticalOptionNames("Short option name '%s' is not unique" format a._1))
    
    if (pargs.find(_._2 == Some("help")).isDefined) {
      vers.foreach(println)
      bann.foreach(println)
      println(help)
      sys.exit(0)
    }
    if (vers.isDefined && pargs.find(_._2 == Some("version")).isDefined) {
      println("version")
      sys.exit(0)
    }
    // check that there are no garbage options
    pargs.foreach { arg =>
      if (!arg._2.map(n => opts.find(_.name == n).isDefined)
          .getOrElse((opts.map(o => o.short.getOrElse(o.name.head)) ++ propts.map(_.char)).find(arg._1.get.head ==).isDefined))
        throw new UnknownOption("Unknown option: %s" format arg._1.getOrElse(arg._2.get))
    }
    
    opts.foreach { o => 
      val sh = getOptShortName(o)
      val params = pargs.filter(a => a._2.map(o.name ==).getOrElse(sh.map(a._1.get.head == _).getOrElse(false))).map(_._3)
      val res = o.conv.parse(params)
      if (res.isLeft) throw new WrongOptionFormat("Wrong format for option '%s': %s" format (o.name, params.map(_.mkString).mkString(" ")))
      if (o.required && !res.right.get.isDefined && !o.default.isDefined) throw new RequiredOptionNotFound("Required option '%s' not found" format o.name)
    }
    this
  }
  
  /** Get summary of current parser state.
      Returns a list of all options in the builder, and corresponding values for them.
    */
  def summary:String = {
    ("Scallop(%s)" format args.mkString(", ")) + "\n" +
    opts.map(o => "  %s => %s" format (o.name, get(o.name)(o.conv.manifest).getOrElse("$None$"))).mkString("\n") + "\n" +
    propts.map(p => "  props %s => %s" format (p.char, propMap(p.char))).mkString("\n") + "\n" + 
    trail.map(t => "  %s => %s" format (t.name, get(t.name)(t.conv.manifest).getOrElse("$None$"))).mkString("\n")
  }
}

/** Parent class for option and property definitions. 
  * @param _name Name for this arg.
  * @param _conv Converter for this arg, holding manifest as well.
  */
abstract class ArgDef(val _name:String,_short:Option[Char], _descr:String, val _conv:ValueConverter[_]) {
  /** The line, that would be printed as definition of this arg in help. */
  def argLine(sh:Option[Char]):String
  /** The full text of definition+description for this arg, as it will appear in options help. */
  def help(sh:Option[Char]):String = {
    var text = List[String]("")
    _descr.split(" ").foreach { w =>
      if (text.last.size + 1 + w.size <= 76) {
        text = text.init :+ (text.last + w + " ")
      } else if (text.last.size + w.size <= 76) {
        text = text.init :+ (text.last + w)
      } else text :+= w
    }
    (argLine(sh) + "\n" + text.map("    " +).mkString("\n")).trim
  }
  
}
/** Holder for option definition.
  * @param name Name for this option, used for identification.
  * @param short Character for short name of this option, defaults to first character of option's name.
  * @param descr Description for this option, used in help printing.
  * @param conv Converter for this options arguments.
  * @param default Default value for this option. Defaults to None.
  * @param required Is this option required? Defaults to false. 
  * @param arg The name for this option argument in help definition.
  */
case class OptDef(name:String, short:Option[Char], descr:String, conv:ValueConverter[_], default:Option[Any], required:Boolean, arg:String) extends ArgDef(name, short, descr, conv) {
  def argLine(sh:Option[Char]):String = List[Option[String]](sh.map("-" +),Some("--" + name)).flatten.mkString(", ") + "  " + conv.argType.fn(arg)
}
/** Holder for property option definition.
  * @param char The char that is used as prefix for property options, and for identification.
  * @param descr Description for this option, used in help printing.
  * @param keyName Name for the 'key' part of option argument, as will be printed in help description.
  * @param valueName Name for the 'value' part of option argument, as will be printed in help description.
  */
case class PropDef(char:Char, descr:String, keyName:String, valueName:String) extends ArgDef(char.toString, Some(char), descr, propsConverter) {
  def argLine(sh:Option[Char]) = "-%1$s%2$s=%3$s [%2$s=%3$s]..." format (char, keyName, valueName)
}
/** Holder for trail argument definition.
  * @param name Name for this definition, used for identification.
  * @param required Is this arg required?
  * @param conv Converter for this argument.
  */
case class TrailDef(name:String, required:Boolean, conv:ValueConverter[_])

/** An enumeration of possible arg types by number of arguments they can take. */
object ArgType extends Enumeration {
  /** Custom class for enumeration type.
    * @param fn Transformation from option name to option's args definition in help. */
  case class V(fn: String => String) extends Val
  /** Option takes no argument. At all. It is either there or it isn't. */
  val FLAG = V(_ => "")
  /** Option takes only one argument. (for example, .opt[Int]) */
  val SINGLE = V("<"+_+">")
  /** Option takes any number of arguments. (.opt[List[Int]]) */
  val LIST = V("<"+_+">...")
}
