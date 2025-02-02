package com.example.demo

import com.example.demo.gql.types.AuthResult
import com.example.demo.gql.types.Comment
import com.example.demo.gql.types.Post
import com.jayway.jsonpath.JsonPath
import com.netflix.graphql.dgs.internal.BaseDgsQueryExecutor.objectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.boot.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.RequestEntity
import org.springframework.web.client.RestTemplate
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketHandler
import org.springframework.web.socket.WebSocketHttpHeaders
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import org.springframework.web.socket.handler.TextWebSocketHandler
import java.net.URI


@SpringBootTest(
    classes = [DemoApplication::class],
    properties = ["context.initializer.classes=com.example.demo.TestConfigInitializer"],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@Import(SubscriptionTests.TestConfig::class)
class SubscriptionTests {

    private val log = LoggerFactory.getLogger(SubscriptionTests::class.java)

    @LocalServerPort
    var port: Int = 8080

    var restTemplate: TestRestTemplate? = null

    @BeforeEach
    fun setup() {
        restTemplate = TestRestTemplate(
            RestTemplateBuilder()
                .defaultMessageConverters()
                .rootUri("http://localhost:$port")
        )
    }

    @Test
    fun `sign in and create a post and comment`() {
        // logged in
        val signinData = mapOf<String, Any>(
            "query" to "mutation signIn(\$input: Credentials!){ signIn(credentials:\$input) {name, roles, token} }",
            "variables" to mapOf(
                "input" to mapOf(
                    "username" to "user",
                    "password" to "password"
                )
            )
        )

        val signinEntity = RequestEntity.post("graphql")
            .accept(MediaType.APPLICATION_JSON)
            .body(signinData)

        val signinResponseEntity = restTemplate!!.exchange(
            signinEntity,
            object : ParameterizedTypeReference<HashMap<String, HashMap<String, AuthResult>>>() {}
        )

        assertThat(signinResponseEntity.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(signinResponseEntity.body!!["data"]!!["signIn"]!!.name).isEqualTo("user")
        assertThat(signinResponseEntity.body!!["data"]!!["signIn"]!!.roles).contains("ROLE_USER")

        val token = signinResponseEntity.body!!["data"]!!["signIn"]!!.token

        assertThat(token).isNotNull

        val requestData = mapOf<String, Any>(
            "query" to "mutation createPost(\$input: CreatePostInput!){ createPost(createPostInput:\$input) {id, title} }",
            "variables" to mapOf(
                "input" to mapOf(
                    "title" to "test title",
                    "content" to "test content"
                )
            )
        )

        val requestEntity = RequestEntity.post("graphql")
            .accept(MediaType.APPLICATION_JSON)
            .headers { it.add("X-Auth-Token", token) }
            .body(requestData)

        val responseEntity = restTemplate!!.exchange(
            requestEntity,
            object : ParameterizedTypeReference<HashMap<String, HashMap<String, Post>>>() {})

        assertThat(responseEntity.statusCode).isEqualTo(HttpStatus.OK)
        val post = responseEntity.body!!["data"]!!["createPost"]
        assertThat(post).isNotNull
        val postId = post!!.id
        assertThat(postId).isNotNull
        assertThat(post.title).isEqualTo("test title")

        val comment1RequestData = mapOf(
            "query" to "mutation addComment(\$input: CommentInput!) { addComment(commentInput:\$input) { id postId content}}",
            "variables" to mapOf(
                "input" to mapOf(
                    "postId" to postId,
                    "content" to "comment1"
                )
            )
        )

        val comment2RequestData = mapOf(
            "query" to "mutation addComment(\$input: CommentInput!) { addComment(commentInput:\$input) { id postId content}}",
            "variables" to mapOf(
                "input" to mapOf(
                    "postId" to postId,
                    "content" to "comment2"
                )
            )
        )

        val comment1Entity = RequestEntity.post("graphql")
            .accept(MediaType.APPLICATION_JSON)
            .headers { it.add("X-Auth-Token", token) }
            .body(comment1RequestData)

        val comment1ResponseEntity = restTemplate!!.exchange(
            comment1Entity,
            object : ParameterizedTypeReference<HashMap<String, HashMap<String, Comment>>>() {})
        assertThat(comment1ResponseEntity.body!!["data"]!!["addComment"]!!.content).isEqualTo("comment1")

        val comment2Entity = RequestEntity.post("graphql")
            .accept(MediaType.APPLICATION_JSON)
            .headers { it.add("X-Auth-Token", token) }
            .body(comment2RequestData)

        val comment2ResponseEntity = restTemplate!!.exchange(
            comment2Entity,
            object : ParameterizedTypeReference<HashMap<String, HashMap<String, Comment>>>() {})
        assertThat(comment2ResponseEntity.body!!["data"]!!["addComment"]!!.content).isEqualTo("comment2")

        val socketClient = StandardWebSocketClient()
        val commentsReplay = ArrayList<String>(2)

        val subscriptionQuery = mapOf(
            "payload" to mapOf(
                "query" to "subscription onCommentAdded { commentAdded { id postId content } }",
                "variables" to emptyMap<String, Any>()// have to add this to avoid a NPE exception.
            ),
            "type" to "start",
            "id" to 1
        )

        // The Dgs webmvc subscription is handled by DgsWebsocketHandler which also implements Apollo subscription websocket protocol.
        // see: https://github.com/apollographql/subscriptions-transport-ws/blob/master/PROTOCOL.md
        // declare a websocket handler to send message and receive message
        val socketHandler: WebSocketHandler = object : TextWebSocketHandler() {
            override fun afterConnectionEstablished(session: org.springframework.web.socket.WebSocketSession) {
                session.sendMessage(TextMessage(objectMapper.writeValueAsBytes(subscriptionQuery)))
            }

            override fun handleTextMessage(
                session: org.springframework.web.socket.WebSocketSession,
                message: TextMessage
            ) {
                log.debug("handling message: {}", message)
                val comment = objectMapper.convertValue(
                    JsonPath.read(message.payload, "$.payload.data.commentAdded"),
                    Comment::class.java
                )
                log.debug("received comments: {}", comment)
                commentsReplay.add(comment.content)
            }
        }

        // do shakehands
        val uri: URI = URI.create("ws://localhost:$port/subscriptions")
        val headers: WebSocketHttpHeaders = WebSocketHttpHeaders(HttpHeaders())

        socketClient.doHandshake(socketHandler, headers, uri)
            .addCallback(
                { log.debug("success: {}", it) },
                { log.error("error: {}", it) }
            )

        Thread.sleep(1000L)

        log.debug("comment replay: {}", commentsReplay)
        // limit to the `latest` item in the `Sinks.replay`
        assertThat(commentsReplay).isEqualTo(arrayListOf("comment2"))
    }

    @TestConfiguration
    class TestConfig {

        @Bean
        fun restTemplate() = RestTemplate()
    }

}