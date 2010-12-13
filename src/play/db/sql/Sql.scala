package play.db.sql
/*
 * sample usage:
 *  val sql=Sql("select * from tasks where id={id}") on "id"->1
 *  val tasks:Stream[Task]=sql.result() collect {
 *    case Row(Some(i:Int),Some(name:String))=> Task(i,name)
 *  }
 *
 *
 *  val in=Sql("select * from tasks where id={id}") on "id"->1 result
 *  ( Task() <~ newLine * )(StreamReader(in))
 *
 *  //having
 *  case class Task(id: Option[Int], name: Option[String]) 
 *  object Task extends MagicParser[Task]
 * 
 */

import play.utils.Scala.MayErr
import play.utils.Scala.MayErr._
abstract class SqlRequestError
case class ColumnNotFound(columnName:String) extends SqlRequestError
case class TypeDoesNotMatch(message:String) extends SqlRequestError
case class UnexpectedNullableFound(on:String) extends SqlRequestError

trait ColumnTo[A]{
 def transform(row:Row,columnName:String):MayErr[SqlRequestError,A]
}
case class StreamReader[T](s: Stream[T]) extends scala.util.parsing.input.Reader[Either[EndOfStream,T]]{
  def first = s.headOption.toRight(EndOfStream())
  def rest = new StreamReader(s.drop(1))
  def pos = scala.util.parsing.input.NoPosition
  def atEnd = s.isEmpty
}
case class EndOfStream
object SqlRowsParser extends scala.util.parsing.combinator.Parsers{
  import Row._
  type Elem=Either[EndOfStream,Row]
  import scala.collection.generic.CanBuildFrom
  import scala.collection.mutable.Builder
  def sequence[A](ps:Traversable[Parser[A]])
                 (implicit bf:CanBuildFrom[Traversable[_], A, Traversable[A]]) =
        Parser[Traversable[A]]{ in => 
           ps.foldLeft(success(bf(ps)))((s,p) =>
            for( ss <- s; pp <- p) yield ss += pp) map (_.result) apply in 
                             }
  implicit def rowFunctionToParser[T](f:(Row => MayErr[SqlRequestError,T])):Parser[T]=
    Parser[T]{in=>
      in.first.left.map(_=>Failure("End of Stream",in))
                   .flatMap(f(_).left.map(e=>Failure(e.toString,in)))
                   .fold(e=>e, a=> {Success(a,in)}) }
  implicit def rowParserToFunction[T](p:RowParser[T]):(Row => MayErr[SqlRequestError,T])=p.f

  case class RowParser[A](f: (Row=>MayErr[SqlRequestError,A])) extends Parser[A]{
    lazy val parser=rowFunctionToParser(f)
    def apply(in:Input)=parser(in)
  }
                  
  def str(columnName:String):RowParser[String]=get[String](columnName)(implicitly[ColumnTo[String]])
  def int(columnName:String):(Row => MayErr[SqlRequestError,Int])=get[Int](columnName)(implicitly[ColumnTo[Int]])
  def get[T](columnName:String)(implicit extractor:ColumnTo[T]):RowParser[T] =
    RowParser(r => extractor.transform(r,columnName))

  def current[T](columnName:String)(implicit extractor:ColumnTo[T]): Parser[T]=
   Parser[T]{in =>
      in.first.left.map(_=>Failure("End of Stream",in))
                   .flatMap(extractor.transform(_,columnName)
                                     .left.map(e=>Failure(e.toString,in)))
                   .fold(e=>e, a=>{Success(a,in)})}   

  def wholeRow[T](p:Parser[T])=p <~ newLine
  def eatRow[T](p:Parser[T])=p <~ newLine
  def current1[T](columnName:String)(implicit extractor:ColumnTo[T]): Parser[T]=
   commit(current[T](columnName)(extractor))
  
  def newLine:Parser[Unit]=  Parser[Unit]{
    in => if(in.atEnd) Failure("end",in) else Success(Unit,in.rest) 
  }

  def group[A](by:(Row=> MayErr[SqlRequestError,Any]),a:Parser[A]):Parser[Seq[A]]={
    val d=guard(by)
    d >> (first => Parser[Seq[A]] {in =>
      {val (groupy,rest)=in.asInstanceOf[StreamReader[Row]]
                           .s.span(by(_).right.toOption.exists(r=>r==first));
       val g=(a *)(StreamReader(groupy))
       g match{
         case Success(a,_)=> Success(a,StreamReader(rest))
         case Failure(msg,_) => Failure(msg,in)
         case Error(msg,_) => Error(msg,in)
       }
       
       }})}
}
trait MagicParser[T]{
  import java.lang.reflect._
  import scala.reflect.Manifest

  def manifestFor(t: Type): Manifest[AnyRef] = t match {
    case c: Class[_] => Manifest.classType[AnyRef](c)
    case p: ParameterizedType =>
      Manifest.classType[AnyRef](
        p.getRawType.asInstanceOf[Class[AnyRef]],
        manifestFor(p.getActualTypeArguments.head),
        p.getActualTypeArguments.tail.map(manifestFor): _*)
  }

  import SqlRowsParser._
  def apply()(implicit m : ClassManifest[T]):Parser[T]={
    def clean(fieldName:String)=fieldName.split('$').last
    val name=clean(m.erasure.getName)
    def isConstructorSupported(c:Constructor[_])=true
    def getParametersNames(c:Constructor[_]):Seq[String]={
      import scala.collection.JavaConversions._
      play.classloading.enhancers.LocalvariablesNamesEnhancer.lookupParameterNames(c)}
    val consInfo= m.erasure
               .getConstructors()
               .sortBy(- _.getGenericParameterTypes().length)
               .find(isConstructorSupported)
               .map(c=>(c,c.getGenericParameterTypes().map(manifestFor),getParametersNames(c)))
               .getOrElse(throw new java.lang.Error("no supported constructors for type " +m))
   val coherent=consInfo._2.length==consInfo._3.length
    val types_names= consInfo._2.zip(consInfo._3)
    if(!coherent && types_names.map(_._2).exists(_.contains("outer")))
      throw new java.lang.Error("It seems that your class uses a closure to an outer instance. For MagicParser, please use only top level classes.")
    if(!coherent) throw new java.lang.Error("not coherent to me!")
    
    implicit def columnToT[T](implicit m:ClassManifest[T]):ColumnTo[T]=
      new ColumnTo[T]{
        def transform(row:Row,columnName:String) =  row.get1[T](columnName)(m)
      }
    val paramParser=sequence(types_names.map(i => 
                       current(clean(name.capitalize+"."+i._2.capitalize))(columnToT((i._1)))))

    paramParser ^^ {case args => 
                      {consInfo._1.newInstance( args.toSeq.map(_.asInstanceOf[Object]):_*)
                           .asInstanceOf[T] } }
  }
}
object Something{

 // def Groupy[K,V](implicit k:ResultReader[K],v:ResultReader[V]): ResultReader[(K,V)]=
  //def Groupy1[K,V](implicit k:ResultReader[V => K],v:ResultReader[V]): ResultReader[K]=

//  def T2[A1,A2](implicit a1:ResultReader[A1],a2:ResultReader[A2]):ResultReader[(A1,A2)]=
}
object Row{
  def unapplySeq(row:Row):Option[List[Any]]={
    Some(row.data.zip(row.metaData.ms.map(_.nullable)).map(i=> if(i._2) Option(i._1) else i._1))
  }

  implicit def rowToString :ColumnTo[String]= 
   new ColumnTo[String]{
     def transform(row:Row,columnName:String) = row.get1[String](columnName) 
   }
  implicit def rowToInt :ColumnTo[Int]= 
   new ColumnTo[Int]{
     def transform(row:Row,columnName:String) = row.get1[Int](columnName) 
   }
  implicit def rowToOption[T](implicit m:ClassManifest[Option[T]]) :ColumnTo[Option[T]]= 
   new ColumnTo[Option[T]]{
     def transform(row:Row,columnName:String) =  row.get1[Option[T]](columnName)(m)
   }
}

case class MetaDataItem(column:String,nullable:Boolean,clazz:String)
case class MetaData(ms:List[MetaDataItem]){
  lazy val dictionary= ms.map(m => (m.column,(m.nullable,m.clazz))).toMap
}

trait Row{
 val metaData:MetaData
  import scala.reflect.Manifest  
  val data:List[Any]
  lazy val ColumnsDictionary:Map[String,Any]=metaData.ms.map(_.column).zip(data).toMap
  def get[A](a:String)(implicit c:ColumnTo[A]):MayErr[SqlRequestError,A]=
    c.transform(this,a)

  private[sql] def get1[B](a:String)(implicit m : ClassManifest[B]):MayErr[SqlRequestError,B]=
   {for(  meta <- metaData.dictionary.get(a).toRight(ColumnNotFound(a));
          val (nullable,clazz)=meta;
          val requiredDataType=
            if(m.erasure==classOf[Option[_]]) 
              m.typeArguments.headOption.collect { case m:ClassManifest[_] => m.erasure}
               .getOrElse(classOf[Any]).getName
            else m.erasure.getName;
          v <- ColumnsDictionary.get(a).toRight(ColumnNotFound(a));
          result <- v match {case b: AnyRef if(nullable != (m.erasure == classOf[Option[_]])) =>  Left(UnexpectedNullableFound(a))
                             case b:AnyRef  if ({requiredDataType} == clazz) =>
                           Right((if (nullable) Option(b) else b).asInstanceOf[B])
                             case b:AnyRef => Left(TypeDoesNotMatch(requiredDataType + " - " + b.getClass.toString))}) yield result
  }
  def apply[B](a:String)(implicit c:ColumnTo[B]):B=get[B](a)(c).get
}

case class MockRow(data: List[Any],metaData:MetaData) extends Row

class SqlRow(rs:java.sql.ResultSet) extends Row{
  import java.sql._
  import java.sql.ResultSetMetaData._
  val meta=rs.getMetaData()
  val nbColumns= meta.getColumnCount()
  val metaData=MetaData(List.range(1,nbColumns+1).map(i=>MetaDataItem(meta.getColumnName(i),meta.isNullable(i)==columnNullable,meta.getColumnClassName(i))))
  val types=
    List.range(1,nbColumns+1)
        .map(i=>(meta.getColumnName(i),meta.getColumnClassName(i)))
        .toMap.lift
  protected def isInitialTypeOK(columnName:String,clazz:Class[_]):Boolean =  types(columnName).exists(t=> clazz.toString==t)
  val data:List[Any]=List.range(1,nbColumns+1).map(nb =>rs.getObject(nb))
}
object Useful{
    case class Var[T](var content:T)
    def drop[A]( these:Var[Stream[A]],n: Int): Stream[A] = {
      var count = n
      while (!these.content.isEmpty && count > 0) {
        these.content = these.content.tail
        count -= 1
      }
    these.content} 
   def unfold1[T, R](init: T)(f: T => Option[(R, T)]): (Stream[R],T) = f(init) match {
     case None => (Stream.Empty,init)
     case Some((r, v)) => (Stream.cons(r,unfold(v)(f)),v)
   }
   def unfold[T, R](init: T)(f: T => Option[(R, T)]): Stream[R] = f(init) match {
     case None => Stream.Empty
     case Some((r, v)) => Stream.cons(r,unfold(v)(f))
   }
}
case class Sql(sqlQuery:String,argsInitialOrder:List[String],params:Seq[(String,Any)]=List.empty){
  def on(args:(String,Any)*):Sql=this.copy(params=(this.params) ++ args)
  def onParams(args:Any*):Sql=this.copy(params=(this.params) ++ argsInitialOrder.zip(args))
  lazy val filledStatement={getFilledStatement(play.db.DB.getConnection)}
  def getFilledStatement(connection:java.sql.Connection)={
    val s=connection.prepareStatement(sqlQuery)
    val argsMap=Map(params:_*)
    argsInitialOrder.map(argsMap)
               .zipWithIndex
               .map(_.swap)
               .foldLeft(s)((s,e)=>{s.setObject(e._1+1,e._2);s})
  }
  def apply()=result()
  def result()=Sql.resultSetToStream(filledStatement.executeQuery())
}
object Sql{
  import SqlParser._
  def apply(inSql:String):Sql={val (sql,paramsNames)= parse(inSql);Sql(sql,paramsNames)}
  import java.sql._
  import java.sql.ResultSetMetaData._
  def resultSetToStream(rs:java.sql.ResultSet):Stream[SqlRow]={
    Useful.unfold(rs)(rs => if(!rs.next()) {rs.close();None} else Some ((new SqlRow(rs),rs)))
  }
}
