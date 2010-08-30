package controllers
 
import play._
import play.mvc._
import play.data.validation._
 
import models._

object Admin extends Controller with Defaults with Secure {
 
    def index = __("posts" -> Posts.find("author", connectedUser).fetch)
    
    def form(id: Long) = __("post" -> Posts.findById(id).orNull)        
    
    def save(id: Long, title: String, content: String, tags: String) {
        val post = if(id == 0) new Post(connectedUser, title, content) else Posts.findById(id).getOrNotFound
        
        post.title = title
        post.content = content
        
        post.tags.clear()
        tags.split("""\s+""") foreach { tag =>
            if(tag.trim().length > 0) {
                post.tags.add(Tags.findOrCreateByName(tag))
            }
        }
        
        if(post.validateAndSave()) {
            @@(index)
        } else {
            "@form".__(post)
        }
        
    }
    
}

// Security

trait Secure extends Controller {
    
    @Before def check = {        
        session("user") match {
            case Some(email) => renderArgs += "user" -> Users.find("byEmail", email).first.getOrNotFound; Continue
            case None => @@(Authentication.login)
        }
    }
    
    @Util def connectedUser = renderArgs.get("user").asInstanceOf[User]
    
}

trait AdminOnly extends Secure {
    
    @Before def checkAdmin = {
        if(!connectedUser.isAdmin) Forbidden else Continue
    }
    
}

object Authentication extends Controller {
    
    def login = __
    
    def authenticate(username: String, password: String) = {
        Users.connect(username, password) match {
            case Some(u) => session.put("user", u.email)
                            @@(Admin.index)
                            
            case None    => flash.error("Oops, bad email or password")
                            flash.put("username", username)
                            @@(login)
        }
    }
    
    def logout = {
        session.clear()
        flash.success("You have been disconnected")
        @@(login)
    }
    
}

// CRUD

package admin {
    
    object Comments extends Controller with CRUDFor[Comment] with AdminOnly
    object Posts extends Controller with CRUDFor[Post] with AdminOnly
    object Tags extends Controller with CRUDFor[Tag] with AdminOnly
    object Users extends Controller with CRUDFor[User] with AdminOnly
    
}
