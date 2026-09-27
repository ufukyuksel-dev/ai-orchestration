package org.springframework.samples.petclinic.owner;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class BenchD2T3Test {

	@Test
	void petHasAnOptionalMicrochipIdThatIsStoredAndShown() throws Exception {
		Pet pet = new Pet();
		Pet.class.getMethod("setMicrochipId", String.class).invoke(pet, "985112004567890");
		assertThat(Pet.class.getMethod("getMicrochipId").invoke(pet)).isEqualTo("985112004567890");
		for (String db : new String[] { "h2", "mysql", "postgres" }) {
			assertThat(Files.readString(Path.of("src/main/resources/db/" + db + "/schema.sql")).toLowerCase())
				.as(db + " schema").contains("microchip");
		}
		assertThat(Files.readString(Path.of("src/main/resources/templates/pets/createOrUpdatePetForm.html")))
			.contains("microchipId");
		assertThat(Files.readString(Path.of("src/main/resources/templates/owners/ownerDetails.html")))
			.contains("microchipId");
	}

}
