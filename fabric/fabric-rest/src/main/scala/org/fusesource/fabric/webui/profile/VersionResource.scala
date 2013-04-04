/*
 * Copyright 2010 Red Hat, Inc.
 *
 *  Red Hat licenses this file to you under the Apache License, version
 *  2.0 (the "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 *  implied.  See the License for the specific language governing
 *  permissions and limitations under the License.
 */
package org.fusesource.fabric.webui.profile

import javax.ws.rs._
import javax.ws.rs.core.{HttpHeaders, Context, MediaType}
import org.codehaus.jackson.annotate.JsonProperty
import org.fusesource.fabric.api.{Profile, Version}
import collection.mutable.ListBuffer
import org.fusesource.fabric.webui._
import java.io._
import javax.ws.rs.core.Response.Status._
import scala.Some
import java.util
import javax.servlet.http.HttpServletRequest
import javax.xml.ws.Response
import scala.Some
import org.apache.commons.io.IOUtils
import org.apache.commons.compress.archivers.zip.{ZipFile, ZipArchiveInputStream, ZipArchiveOutputStream}
import com.sun.jersey.multipart.FormDataParam
import com.sun.jersey.core.header.FormDataContentDisposition
import java.util.{Date, Properties}
import scala.Some
import org.fusesource.fabric.webui._
import scala.Some

class CreateProfileDTO {
  @JsonProperty
  var id: String = _
  @JsonProperty
  var parents: Array[String] = _

  override def toString = String.format("id = %s parents = %s", id, parents)
}

class DeleteProfilesDTO {
  @JsonProperty
  var ids: Array[String] = _
}

object VersionResource {

  def create_profile(self: Version, configs: util.HashMap[String, Array[Byte]], name: String) = {
    var parents = ""

    Option(configs.get("org.fusesource.fabric.agent.properties")) match {
      case Some(agent_properties_bytes) =>
        configs.get("org.fusesource.fabric.agent.properties")
        val agent_properties = new Properties()
        agent_properties.load(new ByteArrayInputStream(agent_properties_bytes))
        parents = agent_properties.remove("parents").asInstanceOf[String]
        val out = new ByteArrayOutputStream
        agent_properties.store(out, "Imported on " + new Date)
        configs.put("org.fusesource.fabric.agent.properties", out.toByteArray)
      case None =>
    }

    val attributes:Properties = Option(configs.remove("attributes.properties")) match {
      case Some(attributes_bytes) =>
        val rc = new Properties
        rc.load(new ByteArrayInputStream(attributes_bytes));
        rc
      case None =>
        new Properties
    }

    val profile = Option[Profile](self.getProfile(name)) match {
      case Some(p) =>
        Services.LOG.info("Overwriting existing profile {}", name)
        p
      case None =>
        Services.LOG.info("Creating profile {}", name)
        self.createProfile(name)
    }

    profile.setFileConfigurations(configs)
    Option[String](parents)  match {
      case Some(parents) =>
        profile.setParents(parents.split(" ").map(self.getProfile(_)))
      case None =>
    }

    import collection.JavaConversions._
    attributes.stringPropertyNames.foreach( (name) => {
      profile.setAttribute(name, attributes.get(name).asInstanceOf[String]);
    })
  }


}

class LightweightProfileResource(val self: Profile) extends BaseResource with HasID {

  @JsonProperty
  def id = self.getId

  @JsonProperty
  def version = self.getVersion

  @JsonProperty
  def is_abstract = Option(self.getAttributes.get(Profile.ABSTRACT)).getOrElse({"false"}).toBoolean

  @JsonProperty
  def is_locked = Option(self.getAttributes.get(Profile.LOCKED)).getOrElse({"false"}).toBoolean

  @JsonProperty
  def is_hidden = Option(self.getAttributes.get(Profile.HIDDEN)).getOrElse({"false"}).toBoolean

  @JsonProperty
  def children = fabric_service.getVersion(self.getVersion).getProfiles.filter(_.getParents.iterator.contains(self)).map(_.getId)

  @JsonProperty
  def agents = self.getAssociatedContainers.map(_.getId)

}

class VersionResource(val self: Version) extends BaseResource with HasID with Exportable {

  import VersionResource._

  @JsonProperty
  def id = self.getId

  @JsonProperty
  def derived_from = Option(self.getDerivedFrom).map(_.getId).getOrElse(null)

  @JsonProperty
  def _default = fabric_service.getDefaultVersion.getId == self.getId

  @JsonProperty
  def agents: Array[String] = fabric_service.getContainers.filter(_.getVersion == self).map(_.getId)

  @JsonProperty
  def _profiles = profiles.map(_.self.getId)

  @JsonProperty
  def abstract_profiles = profiles.filter(_.is_abstract).map(_.id)

  @JsonProperty
  def hidden_profiles = profiles.filter(_.is_hidden).map(_.id)

  @GET
  @Path("profiles")
  def profiles: Array[LightweightProfileResource] = self.getProfiles().map(new
LightweightProfileResource(_)).sortWith(ByID(_, _))

  def full_profiles: Array[ProfileResource] = self.getProfiles().map(new ProfileResource(_)).sortWith(ByID(_, _))

  @Path("profiles/{id}")
  def profiles(@PathParam("id") id: String, @QueryParam("overlay") overlay: Boolean): ProfileResource = {
    val rc = profiles.find(_.id == id) getOrElse {
      not_found
    }

    if (overlay) {
      new ProfileResource(rc.self.getOverlay)
    } else {
      new ProfileResource(rc.self)
    }
  }

  @GET
  @Path("export/{name}.zip")
  @Produces(Array("application/x-zip-compressed"))
  override def do_export(@PathParam("name") name: String) = super.do_export(name)

  def export(out: OutputStream): Unit = {
    val temp = File.createTempFile("exp", "zip")
    val zip = new ZipArchiveOutputStream(temp)
    full_profiles.foreach(_.write_to_zip(zip))
    zip.close()

    val in = new FileInputStream(temp)
    IOUtils.copy(in, out)
    in.close
    temp.delete

  }

  @POST
  @Path("profiles")
  def create(options: CreateProfileDTO) = {
    require(options.id != null, "id cannot be null")
    val p: Profile = self.createProfile(options.id)

    if (options.parents.length > 0) {
      val all_profiles: Array[Profile] = self.getProfiles
      val parents = new ListBuffer[Profile]
      options.parents.foreach((x) => {
        all_profiles.find((y) => y.getId == x) match {
          case Some(p) =>
            parents.append(p)
          case None =>
        }
      })
      p.setParents(parents.toArray)
    }
    new ProfileResource(p)
  }

  @POST
  @Path("import")
  @Consumes(Array("multipart/form-data"))
  @Produces(Array("text/html"))
  def import_profile(@FormDataParam("import-file") file: InputStream,
                     @FormDataParam("import-file") file_detail: FormDataContentDisposition): String = {


    val filename = file_detail.getFileName
    if (!filename.endsWith(".zip")) {
      respond(BAD_REQUEST, "Profile must be stored in a .zip file")
    }

    var name = filename.replace(".zip", "")
    Services.LOG.debug("Received file : {}", filename)

    val tmp = File.createTempFile("imp", ".zip")
    tmp.deleteOnExit()
    val fout = new FileOutputStream(tmp)
    IOUtils.copy(file, fout)
    fout.close

    val zip = new ZipFile(tmp)

    val configs = new util.HashMap[String, Array[Byte]]

    import collection.JavaConverters._

    zip.getEntries.asScala.foreach((x) => {

      if (!x.isDirectory()) {
        val prop_name = x.getName.substring(x.getName.lastIndexOf("/") + 1).replace("/", "")
        val baos = new ByteArrayOutputStream()

        Services.LOG.debug("Found entry {}", prop_name)
        Services.LOG.debug("Entry is (supposedly) {} bytes", x.getSize)

        IOUtils.copy(zip.getInputStream(x), baos);

        val buffer = baos.toByteArray();

        Services.LOG.debug("Read {} bytes", buffer.length)

        configs.put(prop_name, buffer)
      } else {
        name = x.getName.replace("/", "")
      }

    })

    zip.close
    tmp.delete

    Services.LOG.debug("Using profile name {}", name)

    create_profile(self, configs, name)
    name

  }


  @POST
  @Path("delete_profiles")
  def delete_profiles(options: DeleteProfilesDTO) = options.ids.foreach(self.getProfile(_).delete)

  @DELETE
  def delete() = {
    self delete
  }


}
