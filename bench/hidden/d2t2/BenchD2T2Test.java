package org.springframework.samples.petclinic.owner;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class BenchD2T2Test {

	@Test
	void petHasAnOptionalNicknameThatIsStoredAndShown() throws Exception {
		Pet pet = new Pet();
		Pet.class.getMethod("setNickname", String.class).invoke(pet, "Fluffy");
		assertThat(Pet.class.getMethod("getNickname").invoke(pet)).isEqualTo("Fluffy");
		for (String db : new String[] { "h2", "mysql", "postgres" }) {
			assertThat(Files.readString(Path.of("src/main/resources/db/" + db + "/schema.sql")).toLowerCase())
				.as(db + " schema").contains("nickname");
		}
		assertThat(Files.readString(Path.of("src/main/resources/templates/pets/createOrUpdatePetForm.html")))
			.contains("nickname");
		assertThat(Files.readString(Path.of("src/main/resources/templates/owners/ownerDetails.html")))
			.contains("nickname");
	}

}
