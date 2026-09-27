package org.springframework.samples.petclinic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

// Own context (unique property) => own in-memory database and own caches.
@SpringBootTest(properties = "bench.hidden=h2t2")
@AutoConfigureMockMvc
class BenchH2T2Test {

	private static final MediaType TEXT_CSV = MediaType.parseMediaType("text/csv");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbc;

	private List<List<String>> csv(String url) throws Exception {
		MockHttpServletResponse response = this.mockMvc.perform(get(url))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse();
		assertThat(response.getContentType()).as("content type of " + url).isNotNull();
		assertThat(MediaType.parseMediaType(response.getContentType()).isCompatibleWith(TEXT_CSV))
			.as("content type of " + url + ": " + response.getContentType())
			.isTrue();
		return parseCsv(response.getContentAsString(StandardCharsets.UTF_8));
	}

	@Test
	void exportsAllVetsOrderedByIdWithJoinedSpecialties() throws Exception {
		// Inserted before the first request, so no cache is stale.
		this.jdbc.update("INSERT INTO specialties (name) VALUES (?)", "x-ray, \"dental\"");
		Integer xray = this.jdbc.queryForObject("SELECT MAX(id) FROM specialties", Integer.class);
		this.jdbc.update("INSERT INTO vets (first_name, last_name) VALUES (?, ?)", "Mia", "Multi");
		Integer mia = this.jdbc.queryForObject("SELECT MAX(id) FROM vets", Integer.class);
		this.jdbc.update("INSERT INTO vet_specialties VALUES (?, 2)", mia);
		this.jdbc.update("INSERT INTO vet_specialties VALUES (?, 3)", mia);
		this.jdbc.update("INSERT INTO vet_specialties VALUES (?, 1)", mia);
		this.jdbc.update("INSERT INTO vets (first_name, last_name) VALUES (?, ?)", "Zed", "Comma, Quote");
		Integer zed = this.jdbc.queryForObject("SELECT MAX(id) FROM vets", Integer.class);
		this.jdbc.update("INSERT INTO vet_specialties VALUES (?, ?)", zed, xray);
		this.jdbc.update("INSERT INTO vet_specialties VALUES (?, 1)", zed);

		List<List<String>> rows = csv("/vets.csv");

		assertThat(rows).hasSize(9);
		assertThat(rows.get(0)).as("header").containsExactly("id", "firstName", "lastName", "specialties");
		// seed data: 1 Carter (none), 2 Leary (radiology), 3 Douglas (surgery, dentistry),
		// 4 Ortega (surgery), 5 Stevens (radiology), 6 Jenkins (none)
		assertThat(rows.get(1)).as("no specialties => empty").containsExactly("1", "James", "Carter", "");
		assertThat(rows.get(2)).containsExactly("2", "Helen", "Leary", "radiology");
		assertThat(rows.get(3)).containsExactly("3", "Linda", "Douglas", "dentistry;surgery");
		assertThat(rows.get(4)).containsExactly("4", "Rafael", "Ortega", "surgery");
		assertThat(rows.get(5)).containsExactly("5", "Henry", "Stevens", "radiology");
		assertThat(rows.get(6)).containsExactly("6", "Sharon", "Jenkins", "");
		assertThat(rows.get(7)).containsExactly(String.valueOf(mia), "Mia", "Multi", "dentistry;radiology;surgery");
		assertThat(rows.get(8)).as("comma and quotes must be quoted")
			.containsExactly(String.valueOf(zed), "Zed", "Comma, Quote", "radiology;x-ray, \"dental\"");

		existingVetPagesStillWork();
	}

	// Called after the data is inserted: the first findAll() fills the "vets" cache.
	private void existingVetPagesStillWork() throws Exception {
		String html = this.mockMvc.perform(get("/vets.html"))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(html).contains("Carter", "Leary");
		this.mockMvc.perform(get("/vets").accept(MediaType.APPLICATION_JSON)).andExpect(status().isOk());
	}

	/**
	 * Strict RFC 4180 parser: records end with CRLF or LF; a double quote may only appear
	 * in a quoted field (doubled). Throws on malformed input.
	 */
	static List<List<String>> parseCsv(String text) {
		List<List<String>> records = new ArrayList<>();
		List<String> record = new ArrayList<>();
		StringBuilder field = new StringBuilder();
		int i = 0;
		int n = text.length();
		if (n == 0) {
			return records;
		}
		while (true) {
			if (i < n && text.charAt(i) == '"') {
				i++;
				while (true) {
					if (i >= n) {
						throw new AssertionError("unterminated quoted field in CSV:\n" + text);
					}
					char c = text.charAt(i++);
					if (c == '"') {
						if (i < n && text.charAt(i) == '"') {
							field.append('"');
							i++;
						}
						else {
							break;
						}
					}
					else {
						field.append(c);
					}
				}
				if (i < n && text.charAt(i) != ',' && text.charAt(i) != '\r' && text.charAt(i) != '\n') {
					throw new AssertionError("unexpected character after closing quote at " + i + " in CSV:\n" + text);
				}
			}
			else {
				while (i < n && text.charAt(i) != ',' && text.charAt(i) != '\r' && text.charAt(i) != '\n') {
					char c = text.charAt(i++);
					if (c == '"') {
						throw new AssertionError("double quote in an unquoted field at " + (i - 1) + " in CSV:\n" + text);
					}
					field.append(c);
				}
			}
			record.add(field.toString());
			field.setLength(0);
			if (i >= n) {
				records.add(record);
				return records;
			}
			char c = text.charAt(i++);
			if (c == ',') {
				continue;
			}
			if (c == '\r') {
				if (i >= n || text.charAt(i) != '\n') {
					throw new AssertionError("bare CR at " + (i - 1) + " in CSV:\n" + text);
				}
				i++;
			}
			records.add(record);
			record = new ArrayList<>();
			if (i >= n) {
				return records;
			}
		}
	}

}
