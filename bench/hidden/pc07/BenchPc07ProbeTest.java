package org.springframework.samples.petclinic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.HtmlUtils;

/**
 * Convention probe (report-only): user-visible texts are message keys in every language
 * file, resolved through the MessageSource for the selected language, never literals in
 * templates.
 */
@SpringBootTest(properties = "bench.hidden=pc07-probe")
@AutoConfigureMockMvc
class BenchPc07ProbeTest {

	private static final String EN = "Visit date must not be before the birth date of the pet";

	private static final Path MESSAGES = Path.of("src/main/resources/messages");

	@Autowired
	private MockMvc mockMvc;

	private static Properties load(Path file) throws IOException {
		Properties props = new Properties();
		try (Reader reader = new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8)) {
			props.load(reader);
		}
		return props;
	}

	private static String norm(String s) {
		String t = s.trim();
		return t.endsWith(".") ? t.substring(0, t.length() - 1) : t;
	}

	private static String key() throws IOException {
		Properties base = load(MESSAGES.resolve("messages.properties"));
		List<String> keys = new ArrayList<>();
		for (String k : base.stringPropertyNames()) {
			if (norm(base.getProperty(k)).equals(EN)) {
				keys.add(k);
			}
		}
		assertThat(keys).as("a key in messages.properties with the text '" + EN + "'").isNotEmpty();
		return keys.get(0);
	}

	@Test
	void textIsAMessageKeyTranslatedInEveryLanguage() throws IOException {
		String key = key();
		List<Path> files;
		try (Stream<Path> s = Files.list(MESSAGES)) {
			files = s.filter(p -> p.getFileName().toString().matches("messages_[a-z]{2}\\.properties"))
				.filter(p -> !p.getFileName().toString().equals("messages_en.properties"))
				.toList();
		}
		assertThat(files).hasSizeGreaterThanOrEqualTo(7);
		for (Path file : files) {
			String value = load(file).getProperty(key);
			assertThat(value).as(key + " in " + file.getFileName()).isNotBlank();
		}
	}

	@Test
	void templatesDoNotContainTheLiteralText() throws IOException {
		try (Stream<Path> s = Files.walk(Path.of("src/main/resources/templates"))) {
			for (Path file : s.filter(Files::isRegularFile).toList()) {
				assertThat(Files.readString(file)).as(file.toString()).doesNotContain(EN);
			}
		}
	}

	@Test
	void messageFollowsTheSelectedLanguage() throws Exception {
		String de = load(MESSAGES.resolve("messages_de.properties")).getProperty(key());
		assertThat(de).isNotBlank();
		MockHttpSession session = new MockHttpSession();
		this.mockMvc.perform(get("/").param("lang", "de").session(session)).andExpect(status().isOk());
		String html = this.mockMvc
			.perform(post("/owners/6/pets/7/visits/new").session(session)
				.param("date", "2012-09-03")
				.param("description", "checkup"))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString(StandardCharsets.UTF_8);
		assertThat(html.contains(de.trim()) || html.contains(HtmlUtils.htmlEscape(de.trim())))
			.as("German page shows '" + de + "'")
			.isTrue();
	}

}
