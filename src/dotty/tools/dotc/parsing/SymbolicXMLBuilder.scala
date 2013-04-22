package dotty.tools
package dotc
package parsing

import scala.collection.{ mutable, immutable }
import scala.xml.{ EntityRef, Text }
import scala.xml.XML.{ xmlns }
import core._
import Flags.Mutable
import Names._, NameOps._, StdNames._, Decorators._, Trees._, TypedTrees._, UntypedTrees._, Constants._
import Symbols._, Contexts._
import util.Positions._
import scala.reflect.internal.util.StringOps.splitWhere
import scala.language.implicitConversions

/** This class builds instance of `Tree` that represent XML.
 *
 *  Note from martin: This needs to have its position info reworked. I don't
 *  understand exactly what's done here. To make validation pass, I set many
 *  positions to be transparent. Not sure this is a good idea for navigating
 *  XML trees in the IDE but it's the best I can do right now. If someone
 *  who understands this part better wants to give it a shot, please do!
 *
 *  @author  Burak Emir
 *  @version 1.0
 */
abstract class SymbolicXMLBuilder(preserveWS: Boolean)(implicit ctx: Context) {

  import Constants.Constant
  import untpd._

  private[parsing] var isPattern: Boolean = _

  private object xmltypes extends ScalaTypeNames {
    val _Comment: TypeName             = "Comment"
    val _Elem: TypeName                = "Elem"
    val _EntityRef: TypeName           = "EntityRef"
    val _Group: TypeName               = "Group"
    val _MetaData: TypeName            = "MetaData"
    val _NamespaceBinding: TypeName    = "NamespaceBinding"
    val _NodeBuffer: TypeName          = "NodeBuffer"
    val _PrefixedAttribute: TypeName   = "PrefixedAttribute"
    val _ProcInstr: TypeName           = "ProcInstr"
    val _Text: TypeName                = "Text"
    val _Unparsed: TypeName            = "Unparsed"
    val _UnprefixedAttribute: TypeName = "UnprefixedAttribute"
  }

  private object xmlterms extends ScalaTermNames {
    val _Null: TermName     = "Null"
    val __Elem: TermName    = "Elem"
    val __Text: TermName    = "Text"
    val _buf: TermName      = "$buf"
    val _md: TermName       = "$md"
    val _plus: TermName     = "$amp$plus"
    val _scope: TermName    = "$scope"
    val _tmpscope: TermName = "$tmpscope"
    val _xml: TermName      = "xml"
  }

  import xmltypes.{_Comment, _Elem, _EntityRef, _Group, _MetaData, _NamespaceBinding, _NodeBuffer,
    _PrefixedAttribute, _ProcInstr, _Text, _Unparsed, _UnprefixedAttribute}

  import xmlterms.{_Null, __Elem, __Text, _buf, _md, _plus, _scope, _tmpscope, _xml}

  // convenience methods
  private def LL[A](x: A*): List[List[A]] = List(List(x: _*))
  private def const(x: Any)(implicit cpos: Position) = Literal(Constants.Constant(x))
  private def wild(implicit cpos: Position) = Ident(nme.WILDCARD)
  private def wildStar(implicit cpos: Position) = Ident(tpnme.WILDCARD_STAR)
  private def _scala(name: Name)(implicit cpos: Position) = Select(Select(Ident(nme.ROOTPKG), nme.scala_), name)
  private def _scala_xml(name: Name)(implicit cpos: Position) = Select(_scala(_xml), name)

  private def _scala_xml_Comment(implicit cpos: Position)            = _scala_xml(_Comment)
  private def _scala_xml_Elem(implicit cpos: Position)               = _scala_xml(_Elem)
  private def _scala_xml_EntityRef(implicit cpos: Position)          = _scala_xml(_EntityRef)
  private def _scala_xml_Group(implicit cpos: Position)              = _scala_xml(_Group)
  private def _scala_xml_MetaData(implicit cpos: Position)           = _scala_xml(_MetaData)
  private def _scala_xml_NamespaceBinding(implicit cpos: Position)   = _scala_xml(_NamespaceBinding)
  private def _scala_xml_NodeBuffer(implicit cpos: Position)         = _scala_xml(_NodeBuffer)
  private def _scala_xml_Null(implicit cpos: Position)               = _scala_xml(_Null)
  private def _scala_xml_PrefixedAttribute(implicit cpos: Position)  = _scala_xml(_PrefixedAttribute)
  private def _scala_xml_ProcInstr(implicit cpos: Position)          = _scala_xml(_ProcInstr)
  private def _scala_xml_Text(implicit cpos: Position)               = _scala_xml(_Text)
  private def _scala_xml_Unparsed (implicit cpos: Position)          = _scala_xml(_Unparsed)
  private def _scala_xml_UnprefixedAttribute(implicit cpos: Position)= _scala_xml(_UnprefixedAttribute)
  private def _scala_xml__Elem(implicit cpos: Position)              = _scala_xml(__Elem)
  private def _scala_xml__Text(implicit cpos: Position)              = _scala_xml(__Text)

  /** Wildly wrong documentation deleted in favor of "self-documenting code." */
  protected def mkXML(
    isPattern: Boolean,
    pre: Tree,
    label: Tree,
    attrs: Tree,
    scope: Tree,
    empty: Boolean,
    children: Seq[Tree])(implicit cpos: Position): Tree =
  {
    def starArgs =
      if (children.isEmpty) Nil
      else List(Typed(makeXMLseq(children), wildStar))

    def pat    = Apply(_scala_xml__Elem, List(pre, label, wild, wild) ::: convertToTextPat(children))
    def nonpat = New(_scala_xml_Elem, List(List(pre, label, attrs, scope, if (empty) Literal(Constant(true)) else Literal(Constant(false))) ::: starArgs))

    if (isPattern) pat else nonpat
  }

  final def entityRef(n: String)(implicit cpos: Position) =
    New(_scala_xml_EntityRef, LL(const(n)))

  // create scala.xml.Text here <: scala.xml.Node
  final def text(txt: String)(implicit cpos: Position): Tree =
    if (isPattern) makeTextPat(const(txt))
    else makeText1(const(txt))

  def makeTextPat(txt: Tree)(implicit cpos: Position)                = Apply(_scala_xml__Text, List(txt))
  def makeText1(txt: Tree)(implicit cpos: Position)                  = New(_scala_xml_Text, LL(txt))
  def comment(text: String)(implicit cpos: Position)  = Comment(const(text))
  def charData(txt: String)(implicit cpos: Position)  = makeText1(const(txt))

  def procInstr(target: String, txt: String)(implicit cpos: Position) =
    ProcInstr(const(target), const(txt))

  protected def Comment(txt: Tree)(implicit cpos: Position)                  = New(_scala_xml_Comment, LL(txt))
  protected def ProcInstr(target: Tree, txt: Tree)(implicit cpos: Position)  = New(_scala_xml_ProcInstr, LL(target, txt))

  /** @todo: attributes */
  def makeXMLpat(n: String, args: Seq[Tree])(implicit cpos: Position): Tree = {
    val (prepat, labpat) = splitPrefix(n) match {
      case (Some(pre), rest)  => (const(pre), const(rest))
      case _                  => (wild, const(n))
    }
    mkXML(true, prepat, labpat, null, null, false, args)
  }

  protected def convertToTextPat(t: Tree)(implicit cpos: Position): Tree = t match {
    case _: Literal => makeTextPat(t)
    case _          => t
  }
  protected def convertToTextPat(buf: Seq[Tree])(implicit cpos: Position): List[Tree] =
    (buf map convertToTextPat).toList

  def parseAttribute(s: String)(implicit cpos: Position): Tree = {
    val ts = scala.xml.Utility.parseAttributeValue(s) map {
      case Text(s)      => text(s)
      case EntityRef(s) => entityRef(s)
    }
    ts.length match {
      case 0 => TypedSplice(tpd.ref(defn.NilModule))
      case 1 => ts.head
      case _ => makeXMLseq(ts.toList)
    }
  }

  def isEmptyText(t: Tree) = t match {
    case Literal(Constant("")) => true
    case _ => false
  }

  /** could optimize if args.length == 0, args.length == 1 AND args(0) is <: Node. */
  def makeXMLseq(args: Seq[Tree])(implicit cpos: Position) = {
    val buffer = ValDef(Modifiers(), _buf, TypeTree(), New(_scala_xml_NodeBuffer, ListOfNil))
    val applies = args filterNot isEmptyText map (t => Apply(Select(Ident(_buf), _plus), List(t)))

    Block(buffer :: applies.toList, Ident(_buf))
  }

  /** Returns (Some(prefix) | None, rest) based on position of ':' */
  def splitPrefix(name: String): (Option[String], String) = splitWhere(name, _ == ':', true) match {
    case Some((pre, rest))  => (Some(pre), rest)
    case _                  => (None, name)
  }

  /** Various node constructions. */
  def group(args: Seq[Tree])(implicit cpos: Position): Tree =
    New(_scala_xml_Group, LL(makeXMLseq(args)))

  def unparsed(str: String)(implicit cpos: Position): Tree =
    New(_scala_xml_Unparsed, LL(const(str)))

  def element(qname: String, attrMap: mutable.Map[String, Tree], empty: Boolean, args: Seq[Tree])(implicit cpos: Position): Tree = {
    val tpos = cpos.transparent
    implicit val cpos: Position = tpos

    def handleNamespaceBinding(pre: String, z: String): Tree = {
      def mkAssign(t: Tree): Tree = Assign(
        Ident(_tmpscope),
        New(_scala_xml_NamespaceBinding, LL(const(pre), t, Ident(_tmpscope)))
      )

      val uri1 = attrMap(z) match {
        case Apply(_, List(uri @ Literal(Constant(_)))) => mkAssign(uri)
        case Select(_, nme.Nil)                         => mkAssign(const(null))  // allow for xmlns="" -- bug #1626
        case x                                          => mkAssign(x)
      }
      attrMap -= z
      uri1
    }

    /** Extract all the namespaces from the attribute map. */
    val namespaces: List[Tree] =
      for (z <- attrMap.keys.toList ; if z startsWith xmlns) yield {
        val ns = splitPrefix(z) match {
          case (Some(_), rest)  => rest
          case _                => null
        }
        handleNamespaceBinding(ns, z)
      }

    val (pre, newlabel) = splitPrefix(qname) match {
      case (Some(p), x) => (p, x)
      case (None, x)    => (null, x)
    }

    def mkAttributeTree(pre: String, key: String, value: Tree) = {
      // XXX this is where we'd like to put Select(value, nme.toString_) for #1787
      // after we resolve the Some(foo) situation.
      val baseArgs = List(const(key), value, Ident(_md))
      val (clazz, attrArgs) =
        if (pre == null) (_scala_xml_UnprefixedAttribute, baseArgs)
                    else (_scala_xml_PrefixedAttribute  , const(pre) :: baseArgs)

      Assign(Ident(_md), New(clazz, LL(attrArgs: _*)))
    }

    def handlePrefixedAttribute(pre: String, key: String, value: Tree)  = mkAttributeTree(pre, key, value)
    def handleUnprefixedAttribute(key: String, value: Tree)             = mkAttributeTree(null, key, value)

    val attributes: List[Tree] =
      for ((k, v) <- attrMap.toList.reverse) yield splitPrefix(k) match {
        case (Some(pre), rest)  => handlePrefixedAttribute(pre, rest, v)
        case _                  => handleUnprefixedAttribute(k, v)
      }

    lazy val scopeDef     = ValDef(Modifiers(), _scope, _scala_xml_NamespaceBinding, Ident(_tmpscope))
    lazy val tmpScopeDef  = ValDef(Modifiers(Mutable), _tmpscope, _scala_xml_NamespaceBinding, Ident(_scope))
    lazy val metadataDef  = ValDef(Modifiers(Mutable), _md, _scala_xml_MetaData, _scala_xml_Null)
    val makeSymbolicAttrs = if (!attributes.isEmpty) Ident(_md) else _scala_xml_Null

    val (attrResult, nsResult) =
      (attributes.isEmpty, namespaces.isEmpty) match {
        case (true ,  true)   => (Nil, Nil)
        case (true , false)   => (scopeDef :: Nil, tmpScopeDef :: namespaces)
        case (false,  true)   => (metadataDef :: attributes, Nil)
        case (false, false)   => (scopeDef :: metadataDef :: attributes, tmpScopeDef :: namespaces)
      }

    val body = mkXML(
      false,
      const(pre),
      const(newlabel),
      makeSymbolicAttrs,
      Ident(_scope),
      empty,
      args
    )

    Block(nsResult, Block(attrResult, body))
  }
}