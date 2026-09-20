package centuryroad.auth.controller;

import centuryroad.auth.config.RequestCorrelationFilter;
import centuryroad.auth.config.OpenApiConfig;
import centuryroad.auth.dto.ApiEnvelope;
import centuryroad.auth.dto.CreateUserRequest;
import centuryroad.auth.dto.DeletedUserResponse;
import centuryroad.auth.dto.UpdateUserRequest;
import centuryroad.auth.dto.UserListPayload;
import centuryroad.auth.dto.UserRegisterAck;
import centuryroad.auth.dto.UserSummaryDto;
import centuryroad.auth.dto.UserUpdateAck;
import centuryroad.auth.exception.ResourceNotFoundException;
import centuryroad.auth.model.User;
import centuryroad.auth.service.AuthService;
import centuryroad.auth.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

/**
 * User administration - admin only. @RequestBody throughout, not @ModelAttribute: a
 * password must never sit in a URL, where it would reach access logs, browser history
 * and any Referer header sent afterwards.
 */
@RestController
@RequestMapping("/api/admin/users")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "User administration", description = "Administrators only.")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@ApiResponse(responseCode = "401", description = "No token, or an invalid or expired one. `error` is UNAUTHENTICATED.",
        content = @Content(schema = @Schema(implementation = ApiEnvelope.class)))
@ApiResponse(responseCode = "403", description = "The caller is signed in but is not an administrator. "
        + "`error` is ACCESS_DENIED.", content = @Content(schema = @Schema(implementation = ApiEnvelope.class)))
public class AdminUserController {

    private static final String USER_ID_POSITIVE = "L'ID dell'utente deve essere un numero positivo.";

    private final AuthService authService;
    private final UserService userService;

    public AdminUserController(AuthService authService, UserService userService) {
        this.authService = authService;
        this.userService = userService;
    }

    private String sessionId() {
        return RequestCorrelationFilter.current();
    }

    @Operation(
            summary = "Create a user",
            description = "The role defaults to `user`. The new account is enabled.")
    @ApiResponse(responseCode = "201", description = "The user was created.")
    @ApiResponse(responseCode = "400", description = "The request is not valid: a missing or malformed email, a "
            + "password shorter than 8 characters, a role that is neither admin nor user. "
            + "`error` is VALIDATION_ERROR.", content = @Content(schema = @Schema(implementation = ApiEnvelope.class)))
    @ApiResponse(responseCode = "409", description = "That email is already registered. `error` is CONFLICT.",
            content = @Content(schema = @Schema(implementation = ApiEnvelope.class)))
    @PostMapping
    public ResponseEntity<ApiEnvelope<UserRegisterAck>> register(@Valid @RequestBody CreateUserRequest request) {
        User user = authService.register(request);
        return new ResponseEntity<>(
                ApiEnvelope.success("Utente registrato con successo dall'amministratore",
                        new UserRegisterAck(user), sessionId()),
                HttpStatus.CREATED);
    }

    @Operation(summary = "List all users", description = "Every account, with no paging.")
    @ApiResponse(responseCode = "200", description = "The users.")
    @GetMapping
    public ResponseEntity<ApiEnvelope<UserListPayload>> getAllUsers() {
        List<UserSummaryDto> users = authService.getAllUsers().stream()
                .map(UserSummaryDto::of)
                .collect(Collectors.toList());
        return ResponseEntity.ok(
                ApiEnvelope.success("Lista utenti recuperata con successo", new UserListPayload(users), sessionId()));
    }

    @Operation(
            summary = "Update a user",
            description = """
                    Only the fields sent are changed. A blank or missing `password` leaves the current one, so
                    changing a role does not need a new password.""")
    @ApiResponse(responseCode = "200", description = "The user after the update.")
    @ApiResponse(responseCode = "400", description = "The request is not valid. `error` is VALIDATION_ERROR.",
            content = @Content(schema = @Schema(implementation = ApiEnvelope.class)))
    @ApiResponse(responseCode = "404", description = "No user has that id. `error` is NOT_FOUND.", content = @Content(schema = @Schema(implementation = ApiEnvelope.class)))
    @ApiResponse(responseCode = "409", description = "That email belongs to another user. `error` is CONFLICT.",
            content = @Content(schema = @Schema(implementation = ApiEnvelope.class)))
    @PutMapping("/{id}")
    public ResponseEntity<ApiEnvelope<UserUpdateAck>> updateUser(
            @Parameter(description = "The user's id, a positive number.", example = "42")
            @PathVariable("id") @Positive(message = USER_ID_POSITIVE) Long id,
            @Valid @RequestBody UpdateUserRequest request) {
        User updated = authService.updateUser(id, request);
        return ResponseEntity.ok(
                ApiEnvelope.success("Utente aggiornato con successo dall'amministratore",
                        new UserUpdateAck(updated), sessionId()));
    }

    @Operation(summary = "Delete a user", description = "Permanent: the account cannot be recovered.")
    @ApiResponse(responseCode = "200", description = "The user was deleted; the answer carries its id.")
    @ApiResponse(responseCode = "404", description = "No user has that id. `error` is NOT_FOUND.", content = @Content(schema = @Schema(implementation = ApiEnvelope.class)))
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiEnvelope<DeletedUserResponse>> deleteUser(
            @Parameter(description = "The user's id, a positive number.", example = "42")
            @PathVariable("id") @Positive(message = USER_ID_POSITIVE) Long id) {
        if (userService.findById(id) == null) {
            throw new ResourceNotFoundException("No user with id " + id,
                    String.format("L'utente con ID %d non esiste.", id));
        }
        userService.deleteById(id);
        return ResponseEntity.ok(ApiEnvelope.success("Utente eliminato con successo", new DeletedUserResponse(id), sessionId()));
    }
}
