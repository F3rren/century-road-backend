package centuryroad.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "The user that was deleted.")
public record DeletedUserResponse(@Schema(description = "The id the deleted user had.", example = "42") Long id) {
}
