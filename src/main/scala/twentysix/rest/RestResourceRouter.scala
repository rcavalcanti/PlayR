package twentysix.rest

import play.core.Router
import play.api.mvc._
import play.api.http.HeaderNames.ALLOW
import scala.runtime.AbstractPartialFunction
import scala.language.implicitConversions
import scala.reflect.runtime.universe._
import play.api.Logger
import scala.annotation.Annotation
import scala.annotation.ClassfileAnnotation
import scala.annotation.StaticAnnotation


sealed trait ResourceWrapperBase {
  val methodNotAllowed = Action { Results.MethodNotAllowed }
  val caps: ResourceCaps.ValueSet
}

sealed trait DefaultCaps{
  val caps = ResourceCaps.ValueSet.empty
}

sealed trait DefaultApply[T] extends DefaultCaps{
  def apply(obj: T, sid: String) = None
}

sealed trait IdentifiedApply[T<:BaseIdentifiedResource] extends DefaultCaps {
  this: ResourceWrapperBase  =>
  def apply(obj: T, sid: String) = obj.fromId(sid).map(_ => methodNotAllowed)
}

trait IdentifiedResourceWrapper[T] extends ResourceWrapperBase{
  type ResultType
  def apply(obj: T, sid: String): Option[ResultType]
}
trait IdentifiedResourceWrapperDefault {
  implicit def defaultImpl[T] = new IdentifiedResourceWrapper[T] with DefaultApply[T]{
    type ResultType = Any
  }
}
object IdentifiedResourceWrapper extends IdentifiedResourceWrapperDefault{
  implicit def identifiedResourceImpl[T<:BaseIdentifiedResource] = new IdentifiedResourceWrapper[T]{
    type ResultType = T#ResourceType
    def apply(obj: T, sid: String) = obj.fromId(sid)
    val caps = ResourceCaps.ValueSet(ResourceCaps.Identity)
  }
}

trait ReadResourceWrapper[T] extends ResourceWrapperBase{
  def apply(obj: T, sid: String): Option[EssentialAction]
  def list(obj: T): EssentialAction
}
trait DefaultReadResourceWrapper {
  implicit def defaultImpl[T] = new ReadResourceWrapper[T] with DefaultApply[T]{
    def list(obj: T) = methodNotAllowed
  }
}
trait IdentifiedReadResourceWrapper extends DefaultReadResourceWrapper{
  implicit def identifiedResourceImpl[T<:BaseIdentifiedResource] = new ReadResourceWrapper[T] with IdentifiedApply[T]{
    def list(obj: T) = methodNotAllowed
  }
}
object ReadResourceWrapper extends IdentifiedReadResourceWrapper{
  implicit def readResourceImpl[T<:BaseResourceRead] = new ReadResourceWrapper[T]{
    def apply(obj: T, sid: String) = obj.fromId(sid).map(obj.read(_))
    def list(obj: T): EssentialAction = obj.list
    val caps = ResourceCaps.ValueSet(ResourceCaps.Read)
  }
}

trait WriteResourceWrapper[T] extends ResourceWrapperBase{
  def apply(obj: T, sid: String): Option[EssentialAction]
}
trait DefaultWriteResourceWrapper {
  implicit def defaultImpl[T] = new WriteResourceWrapper[T]  with DefaultApply[T]
}
trait IdentifiedWriteResourceWrapper extends DefaultWriteResourceWrapper{
  implicit def identifiedResourceImpl[T<:BaseIdentifiedResource] = new WriteResourceWrapper[T] with IdentifiedApply[T]
}
object WriteResourceWrapper extends IdentifiedWriteResourceWrapper{
  implicit def writeResourceImpl[T<:BaseResourceWrite] = new WriteResourceWrapper[T]{
    def apply(obj: T, sid: String) = obj.fromId(sid).map(obj.write(_))
    val caps = ResourceCaps.ValueSet(ResourceCaps.Write)
  }
}


trait UpdateResourceWrapper[T] extends ResourceWrapperBase{
  def apply(obj: T, sid: String): Option[EssentialAction]
}
trait DefaultUpdateResourceWrapper {
  implicit def defaultImpl[T] = new UpdateResourceWrapper[T]  with DefaultApply[T]
}
trait IdentifiedUpdateResourceWrapper extends DefaultUpdateResourceWrapper{
  implicit def identifiedResourceImpl[T<:BaseIdentifiedResource] = new UpdateResourceWrapper[T] with IdentifiedApply[T]
}
object UpdateResourceWrapper extends IdentifiedUpdateResourceWrapper{
  implicit def updateResourceImpl[T<:BaseResourceUpdate] = new UpdateResourceWrapper[T]{
    def apply(obj: T, sid: String) = obj.fromId(sid).map(obj.update(_))
    val caps = ResourceCaps.ValueSet(ResourceCaps.Update)
  }
}

trait DeleteResourceWrapper[T] extends ResourceWrapperBase{
  def apply(obj: T, sid: String): Option[EssentialAction]
}
trait DefaultDeleteResourceWrapper{
  implicit def defaultImpl[T] = new DeleteResourceWrapper[T] with DefaultApply[T]
}
trait IdentifiedDeleteResourceWrapper extends DefaultDeleteResourceWrapper{
  implicit def identifiedResourceImpl[T<:BaseIdentifiedResource] = new DeleteResourceWrapper[T] with IdentifiedApply[T]
}
object DeleteResourceWrapper extends IdentifiedDeleteResourceWrapper{
  implicit def deleteResourceImpl[T<:BaseResourceDelete] = new DeleteResourceWrapper[T]{
    def apply(obj: T, sid: String) = obj.fromId(sid).map(obj.delete(_))
    val caps = ResourceCaps.ValueSet(ResourceCaps.Delete)
  }
}

trait CreateResourceWrapper[T] extends ResourceWrapperBase{
  def apply(obj: T): EssentialAction
}
trait DefaultCreateResourceWrapper {
  implicit def defaultImpl[T] = new CreateResourceWrapper[T] with DefaultCaps{
    def apply(obj: T) = methodNotAllowed
  }
}
object CreateResourceWrapper extends DefaultCreateResourceWrapper{
  implicit def createResourceImpl[T<:ResourceCreate] = new CreateResourceWrapper[T]{
    def apply(obj: T) = obj.create
    val caps = ResourceCaps.ValueSet(ResourceCaps.Create)
  }
}

trait RouteResourceWrapper[T] extends ResourceWrapperBase{
  def handleRoute(obj: T, requestHeader: RequestHeader, prefixLength: Int, subPrefix: String, sid: String, subPath: String): Option[Handler]
  def routeResources(obj: T): Seq[RestRouteInfo]
}
trait DefaultRouteResourceWrapper {
  implicit def defaultImpl[T<:Resource] = new RouteResourceWrapper[T] with DefaultCaps{
    def handleRoute(obj: T, requestHeader: RequestHeader, prefixLength: Int, subPrefix: String, sid: String, subPath: String) = None
    def routeResources(obj: T) = Seq()
  }
}
object RouteResourceWrapper extends DefaultRouteResourceWrapper{
  implicit def createResourceImpl[T<:BaseResourceRoutes] = new RouteResourceWrapper[T]{
    def handleRoute(obj: T, requestHeader: RequestHeader, prefixLength: Int, subPrefix: String, sid: String, subPath: String) = {
      for {
        action <- obj.routeMap.routeMap.get(subPath)
        id <- obj.fromId(sid)
        res <- action.routing(id, requestHeader, requestHeader.path.take(prefixLength+subPrefix.length()))
      } yield res
    }
    def routeResources(obj: T) = obj.routeMap.routeMap.map { t => t._2.routeInfo(t._1) }.toSeq
    val caps = ResourceCaps.ValueSet(ResourceCaps.Parent)
  }
}

trait ResourceWrapper[T]{
  val identifiedWrapper: IdentifiedResourceWrapper[T]
  val readWrapper: ReadResourceWrapper[T]
  val writeWrapper: WriteResourceWrapper[T]
  val updateWrapper: UpdateResourceWrapper[T]
  val deleteWrapper: DeleteResourceWrapper[T]
  val createWrapper: CreateResourceWrapper[T]
  val routeWrapper: RouteResourceWrapper[T]
  val controllerType: Type
}
object ResourceWrapper {
  implicit def resourceWrapperImpl[C<:Controller with Resource:
                                   TypeTag:
                                   IdentifiedResourceWrapper:
                                   ReadResourceWrapper:
                                   WriteResourceWrapper:
                                   UpdateResourceWrapper:
                                   DeleteResourceWrapper:
                                   CreateResourceWrapper:
                                   RouteResourceWrapper] =
    new ResourceWrapper[C] {
    val identifiedWrapper = implicitly[IdentifiedResourceWrapper[C]]
    val readWrapper = implicitly[ReadResourceWrapper[C]]
    val writeWrapper = implicitly[WriteResourceWrapper[C]]
    val updateWrapper = implicitly[UpdateResourceWrapper[C]]
    val deleteWrapper = implicitly[DeleteResourceWrapper[C]]
    val createWrapper = implicitly[CreateResourceWrapper[C]]
    val routeWrapper = implicitly[RouteResourceWrapper[C]]
    val controllerType = typeOf[C]
  }
}

class RestResourceRouter[C<:Controller with Resource: ResourceWrapper]
    (val controller: C) extends RestRouter with SimpleRouter{

  private val methodNotAllowed = Action { Results.MethodNotAllowed }
  private val IdExpression = "^/([^/]+)/?$".r
  private val SubResourceExpression = "^(/([^/]+)/([^/]+)).*$".r

  val wrapper = implicitly[ResourceWrapper[C]]
  val caps = wrapper.identifiedWrapper.caps ++
             wrapper.readWrapper.caps ++
             wrapper.writeWrapper.caps ++
             wrapper.updateWrapper.caps ++
             wrapper.deleteWrapper.caps ++
             wrapper.createWrapper.caps ++
             wrapper.routeWrapper.caps

  private val ROOT_OPTIONS = Map(
    ResourceCaps.Read   -> "GET",
    ResourceCaps.Create -> "POST"
  )
  private val ID_OPTIONS = Map(
    ResourceCaps.Read   -> "GET",
    ResourceCaps.Delete -> "DELETE",
    ResourceCaps.Write  -> "PUT",
    ResourceCaps.Update -> "PATCH"
  )

  def optionsRoutingHandler(map: Map[ResourceCaps.Value, String]) = Action {
    val options = map.filterKeys( caps contains _).values mkString ","
    Results.Ok.withHeaders(ALLOW -> options)
  }

  def rootOptionsRoutingHandler = optionsRoutingHandler(ROOT_OPTIONS)
  def idOptionsRoutingHandler = optionsRoutingHandler(ID_OPTIONS)

  def routeResources(root: String) = Seq(routerRouteResource(root))
  def routerRouteResource(root: String) = ApiRestRouteInfo(root, controller.name, wrapper.controllerType, caps, wrapper.routeWrapper.routeResources(controller))

  def routeRequest(requestHeader: RequestHeader, path: String, method: String) = {
    path match {
      case SubResourceExpression(subPrefix, id, subPath) =>
        wrapper.routeWrapper.handleRoute(controller, requestHeader, _prefix.length(), subPrefix, id, subPath)
      case "" | "/" => method match {
        case "GET"     => Some(wrapper.readWrapper.list(controller))
        case "POST"    => Some(wrapper.createWrapper(controller))
        case "OPTIONS" => Some(rootOptionsRoutingHandler())
        case _         => Some(methodNotAllowed)
      }
      case IdExpression(sid) => { method match {
        case "GET"     => wrapper.readWrapper(controller, sid)
        case "PUT"     => wrapper.writeWrapper(controller, sid)
        case "DELETE"  => wrapper.deleteWrapper(controller, sid)
        case "PATCH"   => wrapper.updateWrapper(controller, sid)
        case "OPTIONS" => wrapper.identifiedWrapper(controller, sid).map(res => idOptionsRoutingHandler())
        case _         => wrapper.identifiedWrapper(controller, sid).map(res => methodNotAllowed)
      }}
      case _  => None
    }
  }
}

class SubRestResourceRouter[P, C<:Controller with SubResource[P, C]: ResourceWrapper] (controller: C) extends RestResourceRouter[C](controller){
  override val caps = ResourceCaps.ValueSet(ResourceCaps.Child) ++
                      wrapper.identifiedWrapper.caps ++
                      wrapper.readWrapper.caps ++
                      wrapper.writeWrapper.caps ++
                      wrapper.updateWrapper.caps ++
                      wrapper.deleteWrapper.caps ++
                      wrapper.createWrapper.caps ++
                      wrapper.routeWrapper.caps
  def withParent(id: P) = new SubRestResourceRouter[P, C](controller.withParent(id))
}
