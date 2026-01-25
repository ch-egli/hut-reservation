package ch.egli.hut

import com.codeborne.selenide.Configuration
import jakarta.mail.*
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import kotlinx.serialization.json.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.openqa.selenium.chrome.ChromeDriver
import org.openqa.selenium.chrome.ChromeOptions
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.net.URI
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.roundToInt

class MainPageTest {

    @BeforeEach
    fun setUp() {
        // Fix the issue https://github.com/SeleniumHQ/selenium/issues/11750
        Configuration.browserCapabilities = ChromeOptions().addArguments("--remote-allow-origins=*")

        // Configuration settings do not work... :-(
        Configuration.browserSize = "1080x800"
        Configuration.headless = true
    }

    @Test
    fun checkAvailability() {
        logger.info("##### ${getCurrentTimestamp()} -- START")

        while (!isAlarmSet) {
            for (hut in hutNames) {
                val hutJsonData = getJsonDataForHut(hut.key) ?: continue

                for (date in preferredDates) {
                    val availability = getAvailabilityForDate(hutJsonData, date)
                    logger.info("${getCurrentTimestamp()} -- ${hut.value} - Anzahl freie Plätze für $date: $availability")

                    if (availability >= MIN_NUMBER_OF_BEDS && !isAlarmSet) {
                        if (!isException(date, hut.key)) {
                            informMe(date, hut.key, hut.value)
                        }
                    }
                }
            }

            // randomize interval to avoid potential filters...
            val sleepTimeInSeconds = generateRandomIntAround(POLL_INTERVAL_IN_SECONDS)
            logger.info("sleeping for $sleepTimeInSeconds seconds...")
            Thread.sleep((1000 * sleepTimeInSeconds).toLong())
        }
    }

    private fun getJsonDataForHut(hutId: Int): JsonArray? {
        val url = URI(HUETTEN_API_URL + hutId).toURL()
        return try {
            val jsonText = url.readText()
            Json.parseToJsonElement(jsonText).jsonArray
        } catch (e: Exception) {
            logger.error("${getCurrentTimestamp()} -- Fehler beim Holen der Daten für Hütte ID: $hutId: ${e.message}", e)
            null
        }
    }

    private fun getAvailabilityForDate(hutData: JsonArray, date: String): Int {
        val result: JsonElement? = hutData.find { element ->
            element.jsonObject["dateFormatted"]?.jsonPrimitive?.content == date
        }

        val numberOfFreeBeds = result?.jsonObject?.get("freeBeds")?.jsonPrimitive?.int!!
        logger.debug("      ${getCurrentTimestamp()} -- freeBeds - $numberOfFreeBeds")

        return numberOfFreeBeds
    }

    private fun informMe(date: String, hutId: Int, hutName: String) {
        logger.warn("${getCurrentTimestamp()} -- ALARM! Es sind genügend Plätze in der $hutName frei!")
        isAlarmSet = true

        val driver = ChromeDriver()
        driver.manage().window().maximize()
        driver.get("$HUETTEN_RESERVATIONS_URL$hutId/wizard")

        if (SEND_MAIL) {
            logger.info("${getCurrentTimestamp()} -- Sende E-Mail...")
            sendEmail(
                "Hüttenalarm für $hutName",
                "Hütte: $hutName. Datum: $date $HUETTEN_RESERVATIONS_URL$hutId/wizard"
            )
        }
    }

    private fun isException(date: String, hutId: Int): Boolean {
        return exceptions[hutId]?.contains(date) == true
    }

    private fun getCurrentTimestamp(): String {
        val time = Calendar.getInstance().time
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
        return formatter.format(time) ?: ""
    }

    private fun sendEmail(subject: String, text: String) {
        val mailSmtpHost: String = System.getenv("MAIL_SMTP_HOST")
        val mailUsername = System.getenv("EMAIL_USERNAME")
        val mailPassword = System.getenv("EMAIL_PASSWORD")

        val mailReceipient = System.getenv("MAIL_RECEIPIENT")

        val props = Properties()
        props["mail.smtp.host"] = mailSmtpHost
        props["mail.smtp.port"] = "587"
        props["mail.smtp.auth"] = "true"
        props["mail.smtp.starttls.enable"] = "true"

        val session = Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication {
                return PasswordAuthentication(mailUsername, mailPassword)
            }
        })

        val message = MimeMessage(session)
        message.setFrom(InternetAddress(mailUsername))
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(mailReceipient))
        message.subject = subject
        message.setText(text)

        try {
            Transport.send(message)
        } catch (e: Exception) {
            logger.error("${getCurrentTimestamp()} -- Fehler beim Senden der E-Mail: ${e.message}", e)
        }
    }

    /**
     * Generates a random number in the range [givenNumber - 33%, givenNumber + 33%]
     */
    fun generateRandomIntAround(baseValue: Int): Int {
        val min = (baseValue * 0.67).roundToInt()
        val max = (baseValue * 1.33).roundToInt()
        return ThreadLocalRandom.current().nextInt(min, max + 1)
    }

    companion object {
        private val logger: Logger = LoggerFactory.getLogger(MainPageTest::class.java)

        private var isAlarmSet = false

        private const val HUETTEN_API_URL = "https://www.hut-reservation.org/api/v1/reservation/getHutAvailability?hutId="
        private const val HUETTEN_RESERVATIONS_URL = "https://www.hut-reservation.org/reservation/book-hut/"

        private const val POLL_INTERVAL_IN_SECONDS = 60

        /* SET THE FOLLOWING VAULES ***************************************************************/

        private const val MIN_NUMBER_OF_BEDS = 3
        private val preferredDates = listOf("26.03.2026", "27.03.2026", "28.03.2026", "26.04.2026", "27.04.2026")
        val hutNames = mapOf(
            213 to "Finsteraarhornhütte",
            9 to "Britanniahütte"
        )

        val exceptions = mapOf(
            9 to listOf("26.03.2026", "27.04.2026"), // Britanniahütte is alread booked on these dates
            213 to listOf() // Finsteraarhornhütte
        )

        /*
        *  If you set SEND_MAIL to true, you have to provide the following environment variables:
        *  - MAIL_SMTP_HOST: your e-mail SMTP host
        *  - EMAIL_USERNAME: your SMTP host's username
        *  - EMAIL_PASSWORD: your SMTP host's password
        *  - MAIL_RECEIPIENT: the e-mail address where the alarm message is sent to
        */
        private const val SEND_MAIL = true

        /******************************************************************************************/
    }
}
