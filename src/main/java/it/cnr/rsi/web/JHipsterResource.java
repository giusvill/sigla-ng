/*
 * Copyright (C) 2019  Consiglio Nazionale delle Ricerche
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU Affero General Public License as
 *     published by the Free Software Foundation, either version 3 of the
 *     License, or (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU Affero General Public License for more details.
 *
 *     You should have received a copy of the GNU Affero General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package it.cnr.rsi.web;

import it.cnr.rsi.domain.Utente;
import it.cnr.rsi.security.ContextAuthentication;
import it.cnr.rsi.security.UserContext;
import it.cnr.rsi.service.UtenteService;
import it.cnr.rsi.web.rest.errors.InvalidPasswordException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Created by francesco on 21/03/17.
 */

@RestController
@RequestMapping("/api")
public class JHipsterResource {

    private static final Logger LOGGER  = LoggerFactory.getLogger(JHipsterResource.class);

    @Autowired
    private Environment env;
    @Autowired
    private UtenteService utenteService;

    @GetMapping("/profile-info")
    public ResponseEntity<Map<String, Object>> profileInfo() {
        List<String> profiles = Arrays.asList(env.getActiveProfiles());
        Map<String, Object> map = new HashMap<String, Object>();
        map.put("activeProfiles", profiles);
        map.put("instituteAcronym", env.getProperty("institute.acronym", "CNR"));
        map.put("urlChangePassword", env.getProperty("security.ldap.change.password.url"));
        map.put("siglaWildflyURL", env.getProperty("sigla.wildfly.url", ""));

        profiles
            .stream()
            .filter(profile -> profile.equalsIgnoreCase("dev"))
            .findAny()
            .ifPresent(profile -> map.put("ribbonEnv", profile));

        return ResponseEntity.ok(map);
    }

    @RequestMapping(value = "/validate-authentication",
        method = {RequestMethod.GET,
            RequestMethod.POST,
            RequestMethod.DELETE,
            RequestMethod.PUT})
    public ResponseEntity<Boolean> login() {
        LOGGER.info("validate login");
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        LOGGER.info("validate login with authentication {}", authentication);
        if (!Optional
            .ofNullable(authentication)
            .map(auth -> auth.getPrincipal())
            .filter(principal -> principal instanceof UserContext)
            .map(o -> true)
            .orElse(false)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(false);
        }
        return ResponseEntity.ok(true);
    }

    @GetMapping("/account")
    public ResponseEntity<UserDetails> account() {
    	LOGGER.info("get account");
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        LOGGER.info("get account with authentication {}", authentication);
        return ResponseEntity.ok(
            Optional
                .ofNullable(authentication)
                .map(Authentication::getPrincipal)
                .filter(UserContext.class::isInstance)
                .map(UserContext.class::cast)
                .map(userContext -> {
                    final Optional<List<Utente>> usersForUid = Optional.ofNullable(
                        utenteService.findUsersForUid(userContext.getLogin())).filter(utentes -> !utentes.isEmpty());
                    if (usersForUid.isPresent()) {
                        userContext.users(usersForUid.get()
                            .stream()
                            .map(utente -> new UserContext(utente))
                            .collect(Collectors.toList()));
                    } else {
                        userContext.users(Collections.singletonList(utenteService.loadUserByUsername(userContext.getLogin())));
                    }
                    SecurityContextHolder.getContext().setAuthentication(new ContextAuthentication(userContext));
                    return userContext;
                })
                .orElse(null)
        );
    }

    @GetMapping("/account/{username}")
    public ResponseEntity<UserDetails> account(@PathVariable String username) {
        LOGGER.info("get account: {}", username);
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return ResponseEntity.ok(
            Optional
            .ofNullable(authentication)
            .map(Authentication::getPrincipal)
            .filter(principal -> principal instanceof UserContext)
            .map(UserContext.class::cast)
            .map(userContext -> {
                final UserContext newUserContext = userContext.changeUsernameAndAuthority(username);
                SecurityContextHolder.getContext().setAuthentication(new ContextAuthentication(newUserContext));
                return newUserContext;
            })
            .orElseThrow(() -> new RuntimeException("something went wrong " + authentication.toString())));
    }

    /**
     * POST  /account/change-password : changes the current user's password
     *
     * @param password the new password
     */
    @PostMapping(path = "/account/change-password")
    public ResponseEntity<Boolean> changePassword(@RequestBody String password) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        final String newPassword = Optional.ofNullable(password)
            .filter(s -> s.length() > 4)
            .filter(s -> s.length() < 50)
            .orElseThrow(() -> new InvalidPasswordException(password));

        final String userId = Optional
            .ofNullable(authentication)
            .map(Authentication::getPrincipal)
            .filter(principal -> principal instanceof UserContext)
            .map(UserContext.class::cast)
            .map(userContext -> userContext.getLogin())
            .orElseThrow(() -> new RuntimeException("something went wrong " + authentication.toString()));
        LOGGER.info("change password for user: {}", userId);
        utenteService.changePassword(userId, password);
        return ResponseEntity.ok(true);
    }
}
