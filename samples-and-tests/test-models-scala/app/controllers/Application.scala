package controllers

import play.mvc.Controller

object Application extends Controller {
    
  def index() { 
    var user= new models.User("sdfd","dsfdf","dsfdf")
    user.save()
    renderHtml(<h1>{user.email}</h1>)
}
}

