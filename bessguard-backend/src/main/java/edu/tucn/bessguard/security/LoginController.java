package edu.tucn.bessguard.security;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

/**
 * @author <a href="mailto:radu.miro@aut.utcluj.ro">Radu Miron</a>
 */
@RestController
@RequestMapping("/login")
@Tag(name = "login")
public class LoginController {

    private final JwtUtil jwtUtil;
    private final UserAccountService accountService;

    public LoginController(JwtUtil jwtUtil, UserAccountService accountService) {
        this.jwtUtil = jwtUtil;
        this.accountService = accountService;
    }

    @PostMapping(consumes = {MediaType.APPLICATION_FORM_URLENCODED_VALUE, MediaType.MULTIPART_FORM_DATA_VALUE})
    public JwtTokenDTO login(@ModelAttribute CredentialsDTO credentialsDTO) {
        return issueToken(credentialsDTO);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public JwtTokenDTO loginJson(@RequestBody CredentialsDTO credentialsDTO) {
        return issueToken(credentialsDTO);
    }

    private JwtTokenDTO issueToken(CredentialsDTO credentialsDTO) {
        accountService.authenticate(credentialsDTO.getUsername(), credentialsDTO.getPassword());
        JwtTokenDTO result = new JwtTokenDTO();
        result.setToken(jwtUtil.generateToken(credentialsDTO.getUsername()));
        return result;
    }
}
