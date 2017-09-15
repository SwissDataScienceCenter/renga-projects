/*
 * Copyright 2017 - Swiss Data Science Center (SDSC)
 * A partnership between École Polytechnique Fédérale de Lausanne (EPFL) and
 * Eidgenössische Technische Hochschule Zürich (ETHZ).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package controllers

import javax.inject.{ Inject, Singleton }

import authorization.JWTVerifierProvider
import ch.datascience.graph.elements.persisted.PersistedVertex
import ch.datascience.graph.naming.NamespaceAndName
import ch.datascience.service.models.projects.{ CreateProjectRequest, SimpleProject }
import ch.datascience.service.models.projects.json._
import ch.datascience.service.utils.ControllerWithBodyParseJson
import play.api.libs.json.{ Format, Json }
import play.api.libs.ws.{ WSClient, WSRequest }
import play.api.mvc._

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Try }

@Singleton
class ExplorerProxyController @Inject() (
    config:                play.api.Configuration,
    jwtVerifier:           JWTVerifierProvider,
    implicit val wsclient: WSClient,
    implicit val ec:       ExecutionContext
) extends Controller with ControllerWithBodyParseJson {

  //Note: we do unchecked PersistedVertex unboxing

  def findProject( identifier: String ): Action[Unit] = Action.async( BodyParsers.parse.empty ) { implicit request =>
    val optId = Try { Some( identifier.toLong ) }.recover {
      case _: NumberFormatException => None
    }.get

    optId.map { id =>
      val wsReq = prepareProxiedRequest( s"/projects/$id" )
      for {
        response <- wsReq.get()
      } yield {
        if ( response.status == 200 ) {
          val vertex = response.json.as[PersistedVertex]
          if ( vertex.id != id ) throw new RuntimeException( s"did not retrieve expected vertex" )
          val project = interpretVertex( vertex )

          Ok( Json.toJson( project ) )
        }
        else {
          val status = new Status( response.status )
          status( response.bodyAsBytes )
        }

      }
    }.getOrElse( Future.successful( NotFound ) )
  }

  def listProjects(): Action[Unit] = Action.async( BodyParsers.parse.empty ) { implicit request =>
    val wsReq = prepareProxiedRequest( "/projects" )
    for {
      response <- wsReq.get()
    } yield {
      if ( response.status == 200 ) {
        val vertices = response.json.as[Seq[PersistedVertex]]
        val projects = vertices.map( interpretVertex )

        Ok( Json.toJson( Map( "projects" -> projects ) ) )
      }
      else {
        val status = new Status( response.status )
        status( response.bodyAsBytes )
      }
    }
  }

  private[this] lazy val baseUrl: String = config.getString( "explorer.proxy.url" ).get

  private[this] def prepareProxiedRequest( path: String )( implicit request: Request[_] ): WSRequest = {
    val url = s"$baseUrl/api/explorer$path"
    val headers = request.headers.headers

    wsclient.url( url ).withHeaders( headers: _* )
  }

  private[this] implicit val pvFormat: Format[PersistedVertex] = ch.datascience.graph.elements.persisted.json.PersistedVertexFormat

  private[this] def interpretVertex( vertex: PersistedVertex ): SimpleProject = {
    val name = vertex.properties( NamespaceAndName( "project:project_name" ) ).values.head.unboxAs[String]
    val labels = vertex.properties.get( NamespaceAndName( "annotation:label" ) ).map( _.values.map( _.unboxAs[String] ).toSet ).getOrElse( Set.empty )
    SimpleProject( vertex.id, name, labels )
  }

}
