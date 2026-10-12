package org.springframework.samples.petclinic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.data.domain.Page;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.samples.petclinic.owner.Owner;
import org.springframework.samples.petclinic.owner.OwnerRepository;
import org.springframework.samples.petclinic.owner.OwnerService;
import org.springframework.stereotype.Service;
import org.springframework.test.web.servlet.MockMvc;

// Own context (unique property) => own in-memory database.
@SpringBootTest(properties = "bench.hidden=pc05")
@AutoConfigureMockMvc
class BenchPc05Test {

	private static final String[] CONTROLLERS = { "OwnerController", "PetController", "VisitController" };

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private OwnerService service;

	@Test
	void ownerServiceHasTheRequiredShape() throws Exception {
		assertThat(Modifier.isPublic(OwnerService.class.getModifiers())).isTrue();
		assertThat(AnnotatedElementUtils.hasAnnotation(OwnerService.class, Service.class)).as("@Service").isTrue();
		Constructor<OwnerService> ctor = OwnerService.class.getConstructor(OwnerRepository.class);
		assertThat(Modifier.isPublic(ctor.getModifiers())).isTrue();
		Method find = OwnerService.class.getMethod("findOwner", int.class);
		assertThat(find.getReturnType()).isEqualTo(Owner.class);
		Method search = OwnerService.class.getMethod("findOwnersByLastName", String.class, int.class);
		assertThat(search.getReturnType()).isEqualTo(Page.class);
		Method save = OwnerService.class.getMethod("save", Owner.class);
		assertThat(save.getReturnType()).isEqualTo(Owner.class);
	}

	@Test
	void controllersUseTheServiceInsteadOfTheRepository() throws Exception {
		for (String name : CONTROLLERS) {
			Class<?> type = Class.forName("org.springframework.samples.petclinic.owner." + name);
			for (Field field : type.getDeclaredFields()) {
				assertThat(OwnerRepository.class.isAssignableFrom(field.getType()))
					.as(name + " field " + field.getName())
					.isFalse();
			}
			for (Constructor<?> ctor : type.getDeclaredConstructors()) {
				assertThat(Arrays.asList(ctor.getParameterTypes())).as(name + " constructor")
					.doesNotContain(OwnerRepository.class);
			}
			assertThat(Arrays.stream(type.getDeclaredFields()).map(Field::getType))
				.as(name + " has an OwnerService field")
				.contains(OwnerService.class);
		}
	}

	@Test
	void serviceBehaves() {
		Owner coleman = this.service.findOwner(6);
		assertThat(coleman.getLastName()).isEqualTo("Coleman");
		assertThat(coleman.getPets()).hasSize(2);
		assertThatThrownBy(() -> this.service.findOwner(9999)).isInstanceOf(IllegalArgumentException.class);

		long total = this.jdbc.queryForObject("SELECT COUNT(*) FROM owners", Long.class);
		assertThat(total).isGreaterThanOrEqualTo(10);
		Page<Owner> first = this.service.findOwnersByLastName("", 1);
		assertThat(first.getContent()).hasSize(5);
		assertThat(first.getTotalElements()).isEqualTo(total);
		assertThat(first.getTotalPages()).isEqualTo((int) ((total + 4) / 5));
		Page<Owner> second = this.service.findOwnersByLastName("", 2);
		assertThat(second.getContent()).hasSize(5);
		assertThat(second.getNumber()).isEqualTo(1);
		assertThat(this.service.findOwnersByLastName("Davis", 1).getContent()).extracting(Owner::getId)
			.containsExactlyInAnyOrder(2, 4);
		assertThat(this.service.findOwnersByLastName("Nobody", 1).getContent()).isEmpty();

		Owner owner = new Owner();
		owner.setFirstName("Sam");
		owner.setLastName("Servicetest");
		owner.setAddress("4, Evans Street");
		owner.setCity("Wollongong");
		owner.setTelephone("4444444444");
		Owner saved = this.service.save(owner);
		assertThat(saved.getId()).isNotNull();
		assertThat(
				this.jdbc.queryForObject("SELECT COUNT(*) FROM owners WHERE last_name = 'Servicetest'", Integer.class))
			.isEqualTo(1);
	}

	@Test
	void pagesBehaveAsBefore() throws Exception {
		long total = this.jdbc.queryForObject("SELECT COUNT(*) FROM owners", Long.class);
		this.mockMvc.perform(get("/owners").param("lastName", "Franklin"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/owners/1"));
		this.mockMvc.perform(get("/owners").param("lastName", "Davis"))
			.andExpect(status().isOk())
			.andExpect(view().name("owners/ownersList"))
			.andExpect(model().attribute("totalItems", 2L));
		this.mockMvc.perform(get("/owners").param("page", "2"))
			.andExpect(status().isOk())
			.andExpect(model().attribute("currentPage", 2))
			.andExpect(model().attribute("totalPages", (int) ((total + 4) / 5)))
			.andExpect(model().attribute("totalItems", total));
		this.mockMvc.perform(get("/owners").param("lastName", "Nobody"))
			.andExpect(status().isOk())
			.andExpect(view().name("owners/findOwners"))
			.andExpect(model().attributeHasFieldErrorCode("owner", "lastName", "notFound"));
		this.mockMvc.perform(get("/owners/6")).andExpect(status().isOk()).andExpect(view().name("owners/ownerDetails"));
		this.mockMvc.perform(get("/owners/6/edit")).andExpect(status().isOk());
		this.mockMvc.perform(get("/owners/6/pets/7/edit")).andExpect(status().isOk());
		this.mockMvc.perform(get("/owners/6/pets/7/visits/new")).andExpect(status().isOk());

		assertThatThrownBy(() -> this.mockMvc.perform(get("/owners/9999")))
			.hasRootCauseInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> this.mockMvc.perform(get("/owners/9999/edit")))
			.hasRootCauseInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> this.mockMvc.perform(get("/owners/9999/pets/new")))
			.hasRootCauseInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> this.mockMvc.perform(get("/owners/1/pets/7/visits/new")))
			.hasRootCauseInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void writesBehaveAsBefore() throws Exception {
		this.mockMvc
			.perform(post("/owners/new").param("firstName", "Joe")
				.param("lastName", "Bloggs")
				.param("address", "123 Caramel Street")
				.param("city", "London")
				.param("telephone", "1316761638"))
			.andExpect(status().is3xxRedirection());
		Integer id = this.jdbc.queryForObject("SELECT id FROM owners WHERE last_name = 'Bloggs'", Integer.class);

		this.mockMvc
			.perform(post("/owners/{id}/edit", id).param("firstName", "Joe")
				.param("lastName", "Bloggs")
				.param("address", "9 Other Street")
				.param("city", "London")
				.param("telephone", "1316761638"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/owners/" + id));
		assertThat(this.jdbc.queryForObject("SELECT address FROM owners WHERE id = ?", String.class, id))
			.isEqualTo("9 Other Street");

		this.mockMvc
			.perform(post("/owners/{id}/pets/new", id).param("name", "Rex")
				.param("type", "dog")
				.param("birthDate", "2020-02-02"))
			.andExpect(status().is3xxRedirection());
		Integer petId = this.jdbc.queryForObject("SELECT id FROM pets WHERE owner_id = ? AND name = 'Rex'",
				Integer.class, id);

		this.mockMvc
			.perform(post("/owners/{id}/pets/new", id).param("name", "Rex")
				.param("type", "dog")
				.param("birthDate", "2020-02-02"))
			.andExpect(status().isOk())
			.andExpect(model().attributeHasFieldErrorCode("pet", "name", "duplicate"));

		this.mockMvc
			.perform(post("/owners/{id}/pets/{petId}/edit", id, petId).param("name", "Rexy")
				.param("type", "dog")
				.param("birthDate", "2020-02-02"))
			.andExpect(status().is3xxRedirection());
		assertThat(this.jdbc.queryForObject("SELECT name FROM pets WHERE id = ?", String.class, petId))
			.isEqualTo("Rexy");

		this.mockMvc
			.perform(post("/owners/{id}/pets/{petId}/visits/new", id, petId).param("date", "2024-03-03")
				.param("description", "checkup"))
			.andExpect(status().is3xxRedirection());
		assertThat(this.jdbc.queryForObject("SELECT COUNT(*) FROM visits WHERE pet_id = ?", Integer.class, petId))
			.isEqualTo(1);
		this.mockMvc.perform(post("/owners/{id}/pets/{petId}/visits/new", id, petId).param("date", "2024-03-03"))
			.andExpect(status().isOk())
			.andExpect(model().attributeHasErrors("visit"));
	}

}
