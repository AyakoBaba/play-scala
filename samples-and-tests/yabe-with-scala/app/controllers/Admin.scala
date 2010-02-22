package controllers
 
import play._
import play.mvc._
import play.data.validation._
 
import models._

@With(Array(classOf[Secure])) 
object Admin extends Controller with Defaults {
    
    @Before
    private def setConnectedUser{
        if(Secure.Security.isConnected()) {
            val user = User.find("byEmail", Secure.Security.connected()).first
            renderArgs += "user" -> user.fullname
        }
    }
 
    def index {
        val posts = Post.find("author.email", Secure.Security.connected()).fetch
        render(posts)
    }
    
    def form(id: Long) {
        if(id != 0) {
            val post = Post.findById(id)
            render(post)
        }
        render()
    }
    
    def save(id: Long, title: String, content: String, tags: String) {
        var post: Post = null
        if(id == 0) {
            // Create post
            val author = User.find("byEmail", Secure.Security.connected()).first;
            post = new Post(author, title, content)
        } else {
            // Retrieve post
            post = Post.findById(id)
            post.title = title
            post.content = content
            post.tags.clear()
        }
        // Set tags list
        tags.split("""\s+""") foreach { tag: String =>
            if(tag.trim().length > 0) {
                post.tags add Tag.findOrCreateByName(tag)
            }
        }
        // Validate
        validation.valid(post)
        if(Validation.hasErrors()) {
            render("@form", post)
        }
        // Save
        post.save()
        index
    }
    
}

// Security

object Security extends Secure.Security {

    private def authentify(username: String, password: String) = {
        User.connect(username, password) != null
    }
    
    private def check(profile: String) = {
        profile match {
            case "admin" => User.find("byEmail", Secure.Security.connected).first.isAdmin
            case _ => false
        }
    }
    
    private def onDisconnected = Application.index
    
    private def onAuthenticated = Admin.index
    
}

// CRUD

@Check(Array("admin")) @With(Array(classOf[Secure])) class Comments extends CRUD
@Check(Array("admin")) @With(Array(classOf[Secure])) class Posts extends CRUD 
@Check(Array("admin")) @With(Array(classOf[Secure])) class Tags extends CRUD
@Check(Array("admin")) @With(Array(classOf[Secure])) class Users extends CRUD 

