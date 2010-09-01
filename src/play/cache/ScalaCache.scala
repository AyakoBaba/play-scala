package play.cache

/**
 * extends the Cache API with two scala specific methods, this is made public via type alias
 */
private[cache] object ScalaCache extends CacheDelegate {

    /**
     *  retrieves value from Cache based on the type parameter
     *  @param key the name of the cache key
     * @param return either the value or None
     */
    def get[T](key: String)(implicit m: ClassManifest[T]): Option[T] = {
      if (key == null) None
      val v = _impl.get(key).asInstanceOf[T] 
      if (v == null) None
      else if (m.erasure.isAssignableFrom(v.asInstanceOf[AnyRef].getClass)) {
        Some(v)
      } else {
        play.Logger.warn("Found a value in cache for key '%s' of type %s where %s was expected", key, v.asInstanceOf[AnyRef].getClass.getName, m.erasure.getName)
        None
      }
    }

    
  
    /**
     *  retrieves value from Cache based on the type parameter
     *  @param key the name of the cache key
     *  @param return either the value or None
     *  @param expiration expiration period
     */
    def get[T](key: String, expiration: String)(getter: => T): T = {
        import play.libs.Time._
        get(key) match {
            case Some(x) => x
            case None => val r = getter
                         set(key, r, expiration)
                         r
        }
    }
   import play.libs.Time._
   private def prefixed(key:String)="__"+key

   def get[T](key:String,window:String,expiration:String)(getter: => Option[T]):Option[T]={
     val cacheIt= (v:T) =>  {println("v is "+v.toString);set(prefixed(key), v,parseDuration(expiration) + parseDuration(window) + "s" )
                             set(key, v,expiration)
                             v}
     get(key).orElse(getter.map(cacheIt)).orElse(get(prefixed(key)))
   }

  import scala.actors.Actor._
  import scala.actors._
  private val cacheActor=
                actor{link{ loop{ react{case Exit(from: Actor, exc: Exception) => {error(exc.toString); from.restart()}}}}
		      loop{
                        react{
                          case (key:String,window:String,expiration:String,f) => get(key,window,expiration){f.asInstanceOf[Function0[Option[Any]]]()}
                          case  _ => None}}}

   private def getFromCache[T](key:String)=Option(_impl.get(key).asInstanceOf[T])

   def getAsync[T](key:String,expiration:String,window:String)(getter: => Option[T]):Option[T]={
    def cacheIt(t: =>Option[T])= get(key,window,expiration)(t)
     getFromCache[T](key).orElse(getFromCache[T](prefixed(key)).map(v=>{val _=cacheActor! (key,window,expiration,()=>getter);v})
			 .orElse(cacheIt(getter))) 
   }
}
