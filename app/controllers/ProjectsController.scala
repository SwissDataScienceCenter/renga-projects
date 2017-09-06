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

import authorization.{ JWTVerifierProvider, ResourcesManagerJWTVerifierProvider }
import ch.datascience.graph.Constants.VertexId
import ch.datascience.graph.elements.mutation.create.CreateVertexOperation
import ch.datascience.graph.elements.mutation.log.model.json._
import ch.datascience.graph.elements.mutation.log.model.{ EventStatus, MutationFailed, MutationResponse, MutationSuccess }
import ch.datascience.graph.elements.mutation.{ GraphMutationClient, Mutation }
import ch.datascience.graph.elements.new_.build.NewVertexBuilder
import ch.datascience.graph.naming.NamespaceAndName
import ch.datascience.graph.values.StringValue
import ch.datascience.service.ResourceManagerClient
import ch.datascience.service.models.projects.{ CreateProjectRequest, SimpleProject }
import ch.datascience.service.models.projects.json._
import ch.datascience.service.models.resource.json.AccessRequestFormat
import ch.datascience.service.security.ProfileFilterAction
import ch.datascience.service.utils.persistence.graph.{ GraphExecutionContextProvider, JanusGraphTraversalSourceProvider }
import ch.datascience.service.utils.persistence.reader.VertexReader
import ch.datascience.service.utils.{ ControllerWithBodyParseJson, ControllerWithGraphTraversal }
import play.api.libs.json.{ JsArray, JsObject, JsString, Json }
import play.api.libs.ws.WSClient
import play.api.mvc._

import scala.concurrent.{ ExecutionContext, Future }

@Singleton
class ProjectsController @Inject() (
    config:                                         play.api.Configuration,
    jwtVerifier:                                    JWTVerifierProvider,
    rmJwtVerifier:                                  ResourcesManagerJWTVerifierProvider,
    graphMutationClientProvider:                    GraphMutationClientProvider,
    implicit val wsclient:                          WSClient,
    implicit val graphExecutionContextProvider:     GraphExecutionContextProvider,
    implicit val janusGraphTraversalSourceProvider: JanusGraphTraversalSourceProvider,
    implicit val vertexReader:                      VertexReader,
    implicit val ec:                                ExecutionContext
) extends Controller with ControllerWithBodyParseJson with ControllerWithGraphTraversal {

  lazy val gc: GraphMutationClient = graphMutationClientProvider.get

  def projectCreate: Action[CreateProjectRequest] = ProfileFilterAction( jwtVerifier.get ).async( bodyParseJson[CreateProjectRequest] ) { implicit request =>
    val token: String = request.headers.get( "Authorization" ).getOrElse( "" )
    val rmc = new ResourceManagerClient( config )
    val name = request.body.name
    val labels = request.body.labels
    val extra = Some( Json.toJson( Map(
      "project" -> JsString( name ),
      "labels" -> JsArray( labels.toSeq.map( JsString ) )
    ) ).as[JsObject] )

    rmc.authorize( AccessRequestFormat, request.body.toAccessRequest( extra ), token ).flatMap { res =>
      val optResult: Option[Future[Result]] = res.map { ag =>
        if ( ag.verifyAccessToken( rmJwtVerifier.get ).extraClaims.equals( extra ) ) {
          val vertex = new NewVertexBuilder()
            .addSingleProperty( "project:project_name", StringValue( name ) )
            .addLabels( labels )
            .addSingleProperty( "resource:owner", StringValue( request.userId ) )
            .addType( NamespaceAndName( "project:project" ) )
            .result()
          val mut = Mutation( Seq( CreateVertexOperation( vertex ) ) )
          gc.postAndWait( mut ).map { ev =>
            val response = ev.status match {
              case EventStatus.Completed( res ) => res
              case EventStatus.Pending          => throw new RuntimeException( s"Expected completed mutation: ${ev.uuid}" )
            }

            val mutationResponse = response.event.as[MutationResponse]
            val projectVertexId = mutationResponse match {
              case MutationSuccess( results ) => ( results.head \ "id" ).as[VertexId]
              case MutationFailed( reason )   => throw new RuntimeException( s"Project creation failed, caused by: $reason" )
            }

            val project = SimpleProject( projectVertexId, name, labels )
            Created( Json.toJson( project ) )
          }
        }
        else Future( InternalServerError( "Resource Manager response is invalid." ) )
      }

      optResult.getOrElse( Future( InternalServerError( "No response from Resource Manager." ) ) )
    }
  }

  private[this] implicit class BuilderCanAddLabels( builder: NewVertexBuilder ) {
    def addLabels( labels: Set[String] ): NewVertexBuilder = {
      labels.foldLeft( builder ) { ( b, label ) =>
        b.addSetProperty( NamespaceAndName( "annotation:label" ), StringValue( label ) )
      }
    }
  }

}
