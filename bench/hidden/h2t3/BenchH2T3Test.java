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
@SpringBootTest(properties = "bench.hidden=h2t3")
@AutoConfigureMockMvc
class BenchH2T3Test {

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
	void exportsAllVisitsOfTheOwnersPetsSortedByDateThenPetName() throws Exception {
		// seed data, owner 6: Samantha (pet 7) visits 2013-01-01 rabies shot, 2013-01-04
		// spayed; Max (pet 8) visits 2013-01-02 rabies shot, 2013-01-03 neutered.
		// Max (id 8) sorts before Samantha (id 7) by name on the same date.
		insertVisit(7, "2013-01-02", "check-up, general");
		insertVisit(8, "2013-01-04", "said \"hi\"\nand left");
		insertVisit(8, "2012-12-31", "new year");

		List<List<String>> rows = csv("/owners/6/visits.csv");

		assertThat(rows.get(0)).as("header").containsExactly("pet", "date", "description");
		assertThat(rows.subList(1, rows.size())).containsExactly(
				List.of("Max", "2012-12-31", "new year"),
				List.of("Samantha", "2013-01-01", "rabies shot"),
				List.of("Max", "2013-01-02", "rabies shot"),
				List.of("Samantha", "2013-01-02", "check-up, general"),
				List.of("Max", "2013-01-03", "neutered"),
				List.of("Max", "2013-01-04", "said \"hi\"\nand left"),
				List.of("Samantha", "2013-01-04", "spayed"));
	}

	@Test
	void ownerWithoutVisitsGetsOnlyTheHeader() throws Exception {
		// owner 1 (George Franklin) has pet Leo without visits
		assertThat(csv("/owners/1/visits.csv")).containsExactly(List.of("pet", "date", "description"));
	}

	@Test
	void unknownOwnerIsNotFound() throws Exception {
		this.mockMvc.perform(get("/owners/9999/visits.csv")).andExpect(status().isNotFound());
	}

	@Test
	void existingOwnerPagesStillWork() throws Exception {
		String html = this.mockMvc.perform(get("/owners/6"))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(html).contains("Samantha", "Max");
		this.mockMvc.perform(get("/owners/6/edit")).andExpect(status().isOk());
		this.mockMvc.perform(get("/owners/6/pets/7/visits/new")).andExpect(status().isOk());
	}

	private void insertVisit(int petId, String date, String description) {
		this.jdbc.update("INSERT INTO visits (pet_id, visit_date, description) VALUES (?, ?, ?)", petId,
				java.sql.Date.valueOf(date), description);
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
