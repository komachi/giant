package model.frontend

import org.apache.pekko.cluster.{Member, MemberStatus}
import play.api.libs.json.Json

case class Node(hostname: String, reachable: Boolean)

object Node {
  implicit val nodeFormat = Json.format[Node]
}
