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

// Own context (unique property) => own in-memory database.
@SpringBootTest(properties = "bench.hidden=h2t1")
@AutoConfigureMockMvc
class BenchH2T1Test {

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
	void exportsAllOwnersOrderedByIdWithRfc4180Quoting() throws Exception {
		this.jdbc.update("INSERT INTO owners (first_name, last_name, address, city, telephone) VALUES (?, ?, ?, ?, ?)",
				"Anna", "Smith, Jr.", "1 Quote Rd.", "Port \"Harbor\"", "6085550011");
		this.jdbc.update("INSERT INTO owners (first_name, last_name, address, city, telephone) VALUES (?, ?, ?, ?, ?)",
				"Line", "Break", "2 Newline Ave.", "Two\nLines", "6085550012");

		List<List<String>> rows = csv("/owners.csv");

		assertThat(rows).hasSize(13);
		assertThat(rows.get(0)).as("header").containsExactly("id", "firstName", "lastName", "city", "telephone");
		assertThat(rows.get(1)).containsExactly("1", "George", "Franklin", "Madison", "6085551023");
		assertThat(rows.get(2)).containsExactly("2", "Betty", "Davis", "Sun Prairie", "6085551749");
		assertThat(rows.get(6)).containsExactly("6", "Jean", "Coleman", "Monona", "6085552654");
		assertThat(rows.get(10)).containsExactly("10", "Carlos", "Estaban", "Waunakee", "6085555487");
		assertThat(rows.get(11)).as("comma and quotes must be quoted")
			.containsExactly("11", "Anna", "Smith, Jr.", "Port \"Harbor\"", "6085550011");
		assertThat(rows.get(12)).as("line break must be quoted")
			.containsExactly("12", "Line", "Break", "Two\nLines", "6085550012");
		List<String> ids = rows.subList(1, rows.size()).stream().map(r -> r.get(0)).toList();
		assertThat(ids).as("ordered by id")
			.containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12");
	}

	@Test
	void existingOwnerPagesStillWork() throws Exception {
		String list = this.mockMvc.perform(get("/owners").param("lastName", ""))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(list).contains("George Franklin");
		this.mockMvc.perform(get("/owners/1")).andExpect(status().isOk());
		this.mockMvc.perform(get("/owners/find")).andExpect(status().isOk());
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
