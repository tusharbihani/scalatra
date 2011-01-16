package org.scalatra
package socketio

import com.glines.socketio.server.transport._
import collection.JavaConversions._
import scala.io.Source
import java.lang.String
import com.glines.socketio.common.DisconnectReason
import com.glines.socketio.server.{SocketIOInbound, Transport, SocketIOSessionManager}
import com.glines.socketio.server.SocketIOInbound.SocketIOOutbound
import com.glines.socketio.server.SocketIOFrame.FrameType
import java.util.concurrent.{CopyOnWriteArrayList, ConcurrentHashMap}
import javax.servlet.http.{HttpServletResponse, HttpServletRequest}
import util.RicherString._

object SocketIOSupport {
  val BUFFER_SIZE_INIT_PARAM = "bufferSize"
  val MAX_IDLE_TIME_INIT_PARAM: String = "maxIdleTime"
  val BUFFER_SIZE_DEFAULT: Int = 8192
  val MAX_IDLE_TIME_DEFAULT: Int = 300 * 1000

  type ConnectHandler = SocketIOClient => Unit
  type DisconnectHandler = (SocketIOClient, DisconnectReason, String) => Unit
  type MessageHandler = (SocketIOClient, FrameType, String) => Unit

  class SocketIOClientBuilder {

    private var _connectHandler: Option[ConnectHandler] = None
    private var _disconnectHandler: Option[DisconnectHandler] = None
    private var _messageHandler: Option[MessageHandler] = None

    def onConnect(callback: ConnectHandler) {
      _connectHandler = Option(callback)
    }

    def onDisconnect(callback: DisconnectHandler) {
      _disconnectHandler = Option(callback)
    }

    def onMessage(callback: MessageHandler) {
      _messageHandler = Option(callback)
    }

    def result = {
      new SocketIOClient {
        def onConnect(out: SocketIOOutbound) = {
          _out = Option(out)
          _connectHandler foreach {
            _(this)
          }
        }

        def onDisconnect(reason: DisconnectReason, message: String) = {
          _disconnectHandler foreach {
            _(this, reason, message)
          }
        }

        def onMessage(messageType: Int, message: String) = {
          _messageHandler foreach {
            _(this, FrameType.fromInt(messageType), message)
          }
        }
      }
    }

  }

  trait SocketIOClient extends SocketIOInbound {
    protected var _out: Option[SocketIOOutbound] = None
    protected[socketio] var clients = new CopyOnWriteArrayList[SocketIOClient]

    def onMessage(messageType: Int, message: String)

    def onDisconnect(reason: DisconnectReason, message: String)

    def onConnect(out: SocketIOOutbound)

    def getProtocol = null

    def send(messageType: FrameType, message: String) {
      _out foreach {
        _.sendMessage(messageType.value, message)
      }
    }

    def send(message: String) {
      _out foreach {
        _.sendMessage(message)
      }
    }

    def broadcast(message: String) {
      clients foreach { cl => if (cl != this) cl.send(message) }
    }

    def close() {
      _out foreach {
        _.close
      }
    }

    def disconnect {
      _out foreach {
        _.disconnect
      }
    }


  }

}

trait SocketIOSupport extends Handler with Initializable {
  self: ScalatraServlet =>

  import SocketIOSupport._

  private var sessionManager: SocketIOSessionManager = null
  private var transports = new ConcurrentHashMap[String, Transport]
  private var _builder: SocketIOClientBuilder = null
  private var _connections = new CopyOnWriteArrayList[SocketIOClient]

  override def initialize(config: Config) {
    val bufferSize = (Option(getServletConfig.getInitParameter(BUFFER_SIZE_INIT_PARAM)) getOrElse BUFFER_SIZE_DEFAULT.toString).toInt
    val maxIdleTime = (Option(getServletConfig.getInitParameter(MAX_IDLE_TIME_INIT_PARAM)) getOrElse MAX_IDLE_TIME_DEFAULT.toString).toInt
    sessionManager = new SocketIOSessionManager

    val websocketTransport = new WebSocketTransport(bufferSize, maxIdleTime)
    val flashsocketTransport = new FlashSocketTransport(bufferSize, maxIdleTime)
    val htmlFileTransport = new HTMLFileTransport(bufferSize, maxIdleTime)
    val xhrMultipartTransport = new XHRMultipartTransport(bufferSize, maxIdleTime)
    val xhrPollingTransport = new XHRPollingTransport(bufferSize, maxIdleTime)
    val jsonpPollingTransport = new JSONPPollingTransport(bufferSize, maxIdleTime)
    transports.put(websocketTransport.getName, websocketTransport)
    transports.put(flashsocketTransport.getName, flashsocketTransport)
    transports.put(htmlFileTransport.getName, htmlFileTransport)
    transports.put(xhrMultipartTransport.getName, xhrMultipartTransport)
    transports.put(xhrPollingTransport.getName, xhrPollingTransport)
    transports.put(jsonpPollingTransport.getName, jsonpPollingTransport)

    transports.values foreach {
      _.init(getServletConfig)
    }
  }

  abstract override def handle(req: HttpServletRequest, res: HttpServletResponse) {
    val path = req.getPathInfo
    if(path.isBlank || path == "/") super.handle(req, res)
    val parts = (if (path.startsWith("/")) path.substring(1) else path).split("/")
    val transport = transports.get(parts(0))
    if(transport == null) {
      super.handle(req, res)
    } else {
      transport.handle(req, res, new Transport.InboundFactory {
        def getInbound(p1: HttpServletRequest, p2: Array[String]) = {
          val client = _builder.result
          _connections.add(client)
          client.clients = _connections
          client
        }
      }, sessionManager)
    }
//    	String path = request.getPathInfo();
//    	if (path == null || path.length() == 0 || "/".equals(path)) {
//    		response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Missing SocketIO transport");
//    		return;
//    	}
//    	if (path.startsWith("/")) path = path.substring(1);
//    	String[] parts = path.split("/");
//
//    	Transport transport = transports.get(parts[0]);
//    	if (transport == null) {
//    		if ("GET".equals(request.getMethod()) && "socket.io.js".equals(parts[0])) {
//				response.setContentType("text/javascript");
//				InputStream is = this.getClass().getClassLoader().getResourceAsStream("com/glines/socketio/socket.io.js");
//				OutputStream os = response.getOutputStream();
//				IO.copy(is, os);
//				return;
//    		} else {
//	    		response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Unknown SocketIO transport");
//	    		return;
//    		}
//    	}
  }

//  get("/:transportName/?.*/?.*/?.*") {
//    println("matched transport")
//    val transport = transports.get(params('transportName))
//    val builder = _builder
//    transport.handle(request, response, new Transport.InboundFactory {
//      def getInbound(p1: HttpServletRequest, p2: Array[String]) = {
//        val client = builder.result
//        _connections.add(client)
//        client.clients = _connections
//        client
//      }
//    }, sessionManager)
//  }
//  post("/:transportName/?.*/?.*") {
//    println("matched transport")
//    val transport = transports.get(params('transportName))
//    val builder = _builder
//    transport.handle(request, response, new Transport.InboundFactory {
//      def getInbound(p1: HttpServletRequest, p2: Array[String]) = {
//        val client = builder.result
//        _connections.add(client)
//        client.clients = _connections
//        client
//      }
//    }, sessionManager)
//  }

  get("/socket.io.js") {
    contentType = "text/javascript"
    val is = getClass.getClassLoader.getResourceAsStream("com/glines/socketio/socket.io.js")
    val p = request.getServletPath.substring(1)
    Source.fromInputStream(is).getLines foreach {
      line =>
        response.getWriter.println(
          line.replace("'socket.io'", "'%s'" format p).replace("socket.io/WebSocketMain", "%s/WebSocketMain" format p))
    }
  }

  //  get("/WebSocketMain.swf") {
  //    contentType = "application/x-shockwave-flash"
  //    val is = getClass.getClassLoader.getResourceAsStream("org/scalatra/socketio/WebSocketMain.swf")
  //    val os = response.getOutputStream
  //    IO.copy(is, os)
  //  }

  def socketio(action: SocketIOClientBuilder => Unit) {
    if (_builder != null) throw new RuntimeException("You can only use 1 socketio method per application")
    _builder = new SocketIOClientBuilder
    action(_builder)
  }

}
