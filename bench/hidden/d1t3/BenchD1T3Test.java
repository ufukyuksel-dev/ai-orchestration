package org.springframework.samples.petclinic.owner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Properties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(VisitController.class)
class BenchD1T3Test {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private OwnerRepository owners;

	@BeforeEach
	void init() {
		Owner owner = new Owner();
		Pet pet = new Pet();
		owner.addPet(pet);
		pet.setId(1);
		given(this.owners.findById(1)).willReturn(Optional.of(owner));
	}

	@Test
	void rejectsVisitDatesInThePast() throws Exception {
		mockMvc
			.perform(post("/owners/{ownerId}/pets/{petId}/visits/new", 1, 1).param("description", "Checkup")
				.param("date", LocalDate.now().minusDays(2).toString()))
			.andExpect(status().isOk())
			.andExpect(model().attributeHasFieldErrorCode("visit", "date", "pastDate"));
		mockMvc
			.perform(post("/owners/{ownerId}/pets/{petId}/visits/new", 1, 1).param("description", "Checkup")
				.param("date", LocalDate.now().toString()))
			.andExpect(status().is3xxRedirection());
		Properties base = new Properties();
		try (Reader reader = Files.newBufferedReader(Path.of("src/main/resources/messages/messages.properties"))) {
			base.load(reader);
		}
		assertThat(base.getProperty("pastDate")).isNotBlank();
	}

}
