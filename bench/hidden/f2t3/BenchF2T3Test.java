package org.springframework.samples.petclinic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.samples.petclinic.owner.Owner;
import org.springframework.samples.petclinic.owner.OwnerRepository;
import org.springframework.samples.petclinic.owner.Pet;
import org.springframework.samples.petclinic.owner.PetType;
import org.springframework.samples.petclinic.owner.PetTypeRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

// Own context (unique property) => own in-memory database.
@SpringBootTest(properties = "bench.hidden=f2t3")
@AutoConfigureMockMvc
class BenchF2T3Test {

	private static final Pattern HREF = Pattern.compile("href=\"([^\"]*)\"");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private OwnerRepository owners;

	@Autowired
	private PetTypeRepository types;

	private void owner(String firstName, String lastName, String... petTypes) {
		Owner owner = new Owner();
		owner.setFirstName(firstName);
		owner.setLastName(lastName);
		owner.setAddress("1 Test Rd.");
		owner.setCity("Testville");
		owner.setTelephone("6085550000");
		int n = 0;
		for (String typeName : petTypes) {
			PetType type = this.types.findPetTypes()
				.stream()
				.filter(t -> t.getName().equals(typeName))
				.findFirst()
				.orElseThrow();
			Pet pet = new Pet();
			pet.setName(firstName + "Pet" + (++n));
			pet.setBirthDate(LocalDate.of(2020, 1, 1));
			pet.setType(type);
			owner.addPet(pet);
		}
		this.owners.save(owner);
	}

	private static int rows(String html) {
		// one details link per listed owner
		return count(html, "href=\"/owners/\\d+\"");
	}

	private static int count(String html, String regex) {
		Matcher m = Pattern.compile(regex).matcher(html);
		int n = 0;
		while (m.find()) {
			n++;
		}
		return n;
	}

	private String html(MockHttpServletRequestBuilder request) throws Exception {
		return this.mockMvc.perform(request)
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
	}

	private static List<String> linksToPage(String html, int page) {
		List<String> links = new ArrayList<>();
		Matcher m = HREF.matcher(html);
		while (m.find()) {
			String href = m.group(1).replace("&amp;", "&");
			if (href.matches(".*[?&]page=" + page + "(&.*)?$")) {
				links.add(href);
			}
		}
		return links;
	}

	@Test
	void ownerSearchFiltersByPetTypeAndKeepsFiltersWhilePaging() throws Exception {
		// seed data: the only hamster (Basil) belongs to owner 2, Betty Davis
		owner("Ann", "Hamsterson", "hamster", "hamster");
		for (String first : new String[] { "Bob", "Cid", "Dan", "Eve", "Fay" }) {
			owner(first, "Hamsterson", "hamster");
		}
		owner("Gus", "Hamsterson", "dog");

		String page1 = html(get("/owners").param("petType", "hamster"));
		String page2 = html(get("/owners").param("petType", "hamster").param("page", "2"));
		assertThat(rows(page1)).as("rows on page 1").isEqualTo(5);
		assertThat(rows(page2)).as("rows on page 2 (each owner listed once)").isEqualTo(2);
		String both = page1 + page2;
		assertThat(both).contains("href=\"/owners/2\"", "Ann Hamsterson").doesNotContain("Gus Hamsterson");
		assertThat(linksToPage(page1, 2)).as("links to page 2")
			.isNotEmpty()
			.allMatch(l -> l.contains("petType=hamster"));

		String combined = html(get("/owners").param("lastName", "Hamsterson").param("petType", "hamster"));
		assertThat(rows(combined)).isEqualTo(5);
		assertThat(linksToPage(combined, 2)).isNotEmpty()
			.allMatch(l -> l.contains("petType=hamster") && l.contains("lastName=Hamsterson"));

		// an empty pet type (as submitted by the search form) means no pet type filter
		String byName = html(get("/owners").param("lastName", "Hamsterson").param("petType", ""));
		assertThat(rows(byName)).isEqualTo(5);
		// no pet type filter: all seven Hamstersons across both pages, each exactly once (order is not specified)
		String byNameAll = byName + html(get("/owners").param("lastName", "Hamsterson").param("petType", "")
			.param("page", "2"));
		for (String first : new String[] { "Ann", "Bob", "Cid", "Dan", "Eve", "Fay", "Gus" }) {
			assertThat(count(byNameAll, first + " Hamsterson")).as(first + " listed once").isEqualTo(1);
		}

		this.mockMvc.perform(get("/owners").param("lastName", "Davis").param("petType", "hamster"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/owners/2"));

		assertThat(html(get("/owners").param("petType", "unicorn"))).contains("id=\"search-owner-form\"");
		assertThat(html(get("/owners/find"))).contains("name=\"petType\"");
	}

}
