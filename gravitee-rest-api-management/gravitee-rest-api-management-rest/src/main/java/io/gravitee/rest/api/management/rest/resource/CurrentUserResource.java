/**
 * Copyright (C) 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.rest.api.management.rest.resource;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import io.gravitee.common.http.MediaType;
import io.gravitee.common.util.Maps;
import io.gravitee.rest.api.exception.InvalidImageException;
import io.gravitee.rest.api.idp.api.authentication.UserDetailRole;
import io.gravitee.rest.api.idp.api.authentication.UserDetails;
import io.gravitee.rest.api.model.*;
import io.gravitee.rest.api.management.rest.model.PagedResult;
import io.gravitee.rest.api.management.rest.model.TokenEntity;
import io.gravitee.rest.api.security.cookies.CookieGenerator;
import io.gravitee.rest.api.security.filter.TokenAuthenticationFilter;
import io.gravitee.rest.api.security.utils.ImageUtils;
import io.gravitee.rest.api.service.TagService;
import io.gravitee.rest.api.service.TaskService;
import io.gravitee.rest.api.service.UserService;
import io.gravitee.rest.api.service.common.GraviteeContext;
import io.gravitee.rest.api.service.common.JWTHelper;
import io.gravitee.rest.api.service.common.JWTHelper.Claims;
import io.gravitee.rest.api.service.exceptions.UserNotFoundException;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import javax.ws.rs.*;
import javax.ws.rs.container.ResourceContext;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.EntityTag;
import javax.ws.rs.core.Request;
import javax.ws.rs.core.Response;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import static io.gravitee.rest.api.management.rest.model.TokenType.BEARER;
import static io.gravitee.rest.api.service.common.JWTHelper.DefaultValues.DEFAULT_JWT_EXPIRE_AFTER;
import static io.gravitee.rest.api.service.common.JWTHelper.DefaultValues.DEFAULT_JWT_ISSUER;
import static javax.ws.rs.core.Response.ok;
import static javax.ws.rs.core.Response.status;

/**
 * @author Azize ELAMRANI (azize.elamrani at graviteesource.com)
 * @author Nicolas GERAUD (nicolas.geraud at graviteesource.com)
 * @author GraviteeSource Team
 */
@Api(tags = {"User"})
public class CurrentUserResource extends AbstractResource {

    private static Logger LOG = LoggerFactory.getLogger(CurrentUserResource.class);

    @Autowired
    private UserService userService;
    @Context
    private HttpServletResponse response;
    @Autowired
    private TaskService taskService;
    @Context
    private ResourceContext resourceContext;
    @Autowired
    private ConfigurableEnvironment environment;
    @Autowired
    private CookieGenerator cookieGenerator;
    @Autowired
    private TagService tagService;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Get the authenticated user")
    public Response getCurrentUser() {
        if (isAuthenticated()) {
            final UserDetails details = getAuthenticatedUserDetails();
            final String userId = details.getUsername();
            final String password = details.getPassword() != null ? details.getPassword() : "";
            UserEntity userEntity;
            try {
                userEntity = userService.findByIdWithRoles(userId);
            } catch (final UserNotFoundException unfe) {
                final String unfeMessage = "User '{}' does not exist.";
                if (LOG.isDebugEnabled()) {
                    LOG.info(unfeMessage, userId, unfe);
                } else {
                    LOG.info(unfeMessage, userId);
                }
                response.addCookie(cookieGenerator.generate(TokenAuthenticationFilter.AUTH_COOKIE_NAME, null));
                return status(Response.Status.UNAUTHORIZED).build();
            }

            List<GrantedAuthority> authorities = new ArrayList<>(details.getAuthorities());

            UserDetails userDetails = new UserDetails(userEntity.getId(), password, authorities);
            userDetails.setId(userEntity.getId());
            userDetails.setFirstname(details.getFirstname());
            userDetails.setLastname(details.getLastname());
            userDetails.setSource(userEntity.getSource());
            userDetails.setSourceId(userEntity.getSourceId());

            if (details.getEmail() == null && "memory".equals(userEntity.getSource()) && userEntity.getEmail() != null) {
                userDetails.setEmail(userEntity.getEmail());
            } else {
                userDetails.setEmail(details.getEmail());
            }

            //convert UserEntityRoles to UserDetailsRoles
            userDetails.setRoles(userEntity.getRoles().
                    stream().
                    map(userEntityRole -> {
                        UserDetailRole userDetailRole = new UserDetailRole();
                        userDetailRole.setScope(userEntityRole.getScope().name());
                        userDetailRole.setName(userEntityRole.getName());
                        userDetailRole.setPermissions(userEntityRole.getPermissions());
                        return userDetailRole;
                    }).collect(Collectors.toList()));

            userDetails.setFirstLogin(1 == userEntity.getLoginCount());

            return ok(userDetails, MediaType.APPLICATION_JSON).build();
        } else {
            return ok().build();
        }
    }

    @PUT
    @ApiOperation(value = "Update user")
    public Response updateCurrentUser(@Valid @NotNull final UpdateUserEntity user) {
        UserEntity userEntity = userService.findById(getAuthenticatedUser());
        try {
            if (user.getPicture() != null) {
                user.setPicture(ImageUtils.verifyAndRescale(user.getPicture()).toBase64());
            }
        } catch (InvalidImageException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity("Invalid image format").build();
        }

        return ok(userService.update(userEntity.getId(), user)).build();
    }

    @GET
    @Path("avatar")
    @ApiOperation(value = "Get user's avatar")
    public Response getCurrentUserPicture(@Context Request request) {
        String userId = userService.findById(getAuthenticatedUser()).getId();
        PictureEntity picture = userService.getPicture(userId);

        if (picture instanceof UrlPictureEntity) {
            return Response.temporaryRedirect(URI.create(((UrlPictureEntity) picture).getUrl())).build();
        }

        InlinePictureEntity image = (InlinePictureEntity) picture;
        if (image == null || image.getContent() == null) {
            return Response.ok().build();
        }

        EntityTag etag = new EntityTag(Integer.toString(new String(image.getContent()).hashCode()));
        Response.ResponseBuilder builder = request.evaluatePreconditions(etag);

        if (builder != null) {
            return builder.build();
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        baos.write(image.getContent(), 0, image.getContent().length);

        return ok()
                .entity(baos)
                .tag(etag)
                .type(image.getType())
                .build();
    }

    @POST
    @Path("/login")
    @ApiOperation(value = "Login")
    @Produces(MediaType.APPLICATION_JSON)
    public Response login(final @Context javax.ws.rs.core.HttpHeaders headers, final @Context HttpServletResponse servletResponse) {
        final Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof UserDetails) {
            // JWT signer
            final Map<String, Object> claims = new HashMap<>();
            claims.put(Claims.ISSUER, environment.getProperty("jwt.issuer", DEFAULT_JWT_ISSUER));

            final UserDetails userDetails = (UserDetails) authentication.getPrincipal();

            // Manage authorities, initialize it with dynamic permissions from the IDP
            List<Map<String, String>> authorities = userDetails.getAuthorities().stream().map(authority -> Maps.<String, String>builder().put("authority", authority.getAuthority()).build()).collect(Collectors.toList());

            // We must also load permissions from repository for configured management or portal role
            Set<RoleEntity> roles = membershipService.getRoles(
                    MembershipReferenceType.ENVIRONMENT,
                    GraviteeContext.getCurrentEnvironment(),
                    MembershipMemberType.USER,
                    userDetails.getUsername());
            if (!roles.isEmpty()) {
                roles.forEach(role-> authorities.add(Maps.<String, String>builder().put("authority", role.getScope().toString() + ':' + role.getName()).build()));
            }

            roles = membershipService.getRoles(
                    MembershipReferenceType.ORGANIZATION,
                    GraviteeContext.getCurrentOrganization(),
                    MembershipMemberType.USER,
                    userDetails.getUsername());
            if (!roles.isEmpty()) {
                roles.forEach(role-> authorities.add(Maps.<String, String>builder().put("authority", role.getScope().toString() + ':' + role.getName()).build()));
            }

            // JWT signer
            Algorithm algorithm = Algorithm.HMAC256(environment.getProperty("jwt.secret"));

            Date issueAt = new Date();
            Instant expireAt = issueAt.toInstant().plus(Duration.ofSeconds(environment.getProperty("jwt.expire-after",
                    Integer.class, DEFAULT_JWT_EXPIRE_AFTER)));

            final String token = JWT.create()
                    .withIssuer(environment.getProperty("jwt.issuer", DEFAULT_JWT_ISSUER))
                    .withIssuedAt(issueAt)
                    .withExpiresAt(Date.from(expireAt))
                    .withSubject(userDetails.getUsername())
                    .withClaim(JWTHelper.Claims.PERMISSIONS, authorities)
                    .withClaim(JWTHelper.Claims.EMAIL, userDetails.getEmail())
                    .withClaim(JWTHelper.Claims.FIRSTNAME, userDetails.getFirstname())
                    .withClaim(JWTHelper.Claims.LASTNAME, userDetails.getLastname())
                    .withJWTId(UUID.randomUUID().toString())
                    .sign(algorithm);


            final TokenEntity tokenEntity = new TokenEntity();
            tokenEntity.setType(BEARER);
            tokenEntity.setToken(token);

            final Cookie bearerCookie = cookieGenerator.generate(TokenAuthenticationFilter.AUTH_COOKIE_NAME, "Bearer%20" + token);
            servletResponse.addCookie(bearerCookie);

            return ok(tokenEntity).build();
        }
        return ok().build();
    }

    @POST
    @Path("/logout")
    @ApiOperation(value = "Logout")
    public Response logout() {
        response.addCookie(cookieGenerator.generate(TokenAuthenticationFilter.AUTH_COOKIE_NAME, null));
        return Response.ok().build();
    }

    @GET
    @Path("/tasks")
    @Produces(MediaType.APPLICATION_JSON)
    public PagedResult getUserTasks() {
        List<TaskEntity> tasks = taskService.findAll(getAuthenticatedUserOrNull());
        Map<String, Map<String, Object>> metadata = taskService.getMetadata(tasks).getMetadata();
        PagedResult<TaskEntity> pagedResult = new PagedResult<>(tasks);
        pagedResult.setMetadata(metadata);
        return pagedResult;
    }

    @GET
    @Path("/tags")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getUserShardingTags() {
        return Response.ok(tagService.findByUser(getAuthenticatedUser())).build();
    }


    @Path("/notifications")
    public UserNotificationsResource getUserNotificationsResource() {
        return resourceContext.getResource(UserNotificationsResource.class);
    }

    @Path("/tokens")
    public TokensResource getTokensResource() {
        return resourceContext.getResource(TokensResource.class);
    }
}
