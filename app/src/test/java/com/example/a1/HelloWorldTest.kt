import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HelloWorldTest {
    @Test
    fun testHelloWorld() {
        assertEquals("Hello, World!", HelloWorld.greet())
    }
}