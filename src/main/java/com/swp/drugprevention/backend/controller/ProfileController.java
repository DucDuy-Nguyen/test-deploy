package com.swp.drugprevention.backend.controller;

import com.swp.drugprevention.backend.enums.RoleName;
import com.swp.drugprevention.backend.exception.EmailAlreadyExistsException;
import com.swp.drugprevention.backend.exception.SameRoleException;
import com.swp.drugprevention.backend.io.request.ProfileRequest;
import com.swp.drugprevention.backend.io.request.RoleUpdateRequest;
import com.swp.drugprevention.backend.io.request.UpdateConsultantProfileRequest;
import com.swp.drugprevention.backend.io.request.UpdateUserProfileRequest;
import com.swp.drugprevention.backend.io.response.ConsultantUpdateResponse;
import com.swp.drugprevention.backend.io.response.ProfileResponse;
import com.swp.drugprevention.backend.model.Consultant;
import com.swp.drugprevention.backend.model.User;
import com.swp.drugprevention.backend.repository.ConsultantRepository;
import com.swp.drugprevention.backend.repository.ProfileService;
import com.swp.drugprevention.backend.repository.UserRepository;
import com.swp.drugprevention.backend.service.ConsultantService;
import com.swp.drugprevention.backend.service.EmailService;
import com.swp.drugprevention.backend.service.ProfileServiceImpl;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
//import org.springframework.security.core.annotation.CurrentSecurityContext;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.CurrentSecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequiredArgsConstructor
public class ProfileController {

    private final ProfileService profileService;
    private final EmailService emailService;
    private final UserRepository userRepository;
    private final ConsultantRepository consultantRepository;
    private final ConsultantService consultantService;
    private final ProfileServiceImpl profileServiceImpl;

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<String> register(@Valid @RequestBody ProfileRequest request) {
        userRepository.findByEmail(request.getEmail())
                .ifPresent(user -> { throw new EmailAlreadyExistsException("Email is exist: "+request.getEmail());
                });
        //nếu không tồn tại thì tạo mới
        /*ProfileResponse response = profileService.createProfile(request);
        emailService.sendWelcomeEmail(response.getEmail(), response.getFullName());//dùng để gửi mail ngay sau khi đăng kí xong
        return response;*/
        profileService.sendOtp(request);
        return ResponseEntity.ok("OTP sent to " + request.getEmail());
    }

    @PutMapping("/{userId}/roles")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ProfileResponse> updateUserRoles(@PathVariable Integer userId, @RequestBody RoleUpdateRequest roleUpdateRequest) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("UserID is not found: " + userId));

        RoleName newRoleName = roleUpdateRequest.getRoleName();
        RoleName currentRoleName = user.getRoleName();

        if (newRoleName == currentRoleName) {
            throw new SameRoleException("The new role '" + newRoleName + "' is the same as the current role.");
        }

        // Nếu vai trò hiện tại là CONSULTANT và vai trò mới không phải CONSULTANT, xóa bản ghi Consultant
        if (currentRoleName == RoleName.CONSULTANT && newRoleName != RoleName.CONSULTANT) {
            consultantRepository.deleteByUser(user);
        }

        // Nếu vai trò mới là CONSULTANT, tạo bản ghi trong bảng Consultants
        if (newRoleName == RoleName.CONSULTANT) {
            if (!consultantRepository.existsByUser(user)) {
                Consultant consultant = new Consultant();
                consultant.setUser(user);
                consultant.setAvailability(true); // Đặt availability mặc định là 1
                consultant.setEmail(user.getEmail());
                consultant.setName(user.getFullName());
                consultant.setSchedule(null);
                consultant.setSpecialization(null);
                consultantRepository.save(consultant);
            }
        }


        user.setRoleName(newRoleName); // Cập nhật roleName
        User updatedUser = userRepository.save(user);

        ProfileResponse profileResponse = profileService.getProfile(updatedUser.getEmail());
        return ResponseEntity.ok()
                .header("X-Custom-Message", "User role updated successfully for user ID: " + userId)
                .body(profileResponse);
    }
    @GetMapping("/profileAllUser")
    //@PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<ProfileResponse>> getAllUserProfile() {
        List<ProfileResponse> allProfiles = profileService.getAllUserProfile();
        return ResponseEntity.ok(allProfiles);
    }

    @DeleteMapping("/delete/{userId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<String> deleteUser(@PathVariable Integer userId) {
        profileServiceImpl.deleteUserById(userId);
        return ResponseEntity.ok("Đã xóa người dùng với ID: " + userId);
    }

    @GetMapping("/profile")
    public ProfileResponse getProfile(@CurrentSecurityContext(expression = "authentication?.name") String email) { //@CurrentSecurityContext(expression = "authentication?.name") dùng cho chức năng cần được xác thực và
        //nếu không được xác thực thì sẽ bị ném ra ngoại lệ được thiết lập trong CustomAuthenticationEntryPoint và cấu hình trong config
        return profileService.getProfile(email);
    }

    // Cập nhật thông tin cá nhân của người dùng hoặc tư vấn viên
    // FE chú ý là phải hiện thông tin theo vai trò người dùng, nếu là tư vấn viên thì hiện thông tin của tư vấn viên,
    // nếu là người dùng thì hiện thông tin của người dùng,
    // vì đang thu thập dựa trên body -> giải thích rõ hơn trong postman
    @PutMapping("/update-my-profile")
    public ResponseEntity<?> updateMyProfile(
            @CurrentSecurityContext(expression = "authentication?.name") String email,
            @RequestBody Map<String, Object> body) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy người dùng với email: " + email));

        RoleName roleName = user.getRoleName();
        if (roleName == RoleName.CONSULTANT) {
            Set<String> allowedFields = Set.of("name", "specialization", "phone", "yob");
            for (String key : body.keySet()) {
                if (!allowedFields.contains(key)) {
                    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                            .body("Cập nhật thông tin tư vấn viên thất bại! Làm ơn điền đúng vào body fields được cho phép theo role: "+ user.getRoleName() +" của bro thôi nhé!");
                }
            }
            //Create consultant from map body
            try {
                UpdateConsultantProfileRequest consultantRequest = new UpdateConsultantProfileRequest();
                consultantRequest.setName((String) body.get("name"));
                consultantRequest.setSpecialization((String) body.get("specialization"));
                consultantRequest.setPhone((String) body.get("phone"));
                consultantRequest.setYob((Integer) body.get("yob"));

                Consultant consultant = consultantRepository.findByUser(user).orElseThrow(() -> new RuntimeException("Không tìm thấy tư vấn viên cho người dùng: " + user.getEmail()));
                consultantService.updateConsultantProfile(consultant, consultantRequest, user);
                ConsultantUpdateResponse consultantUpdateResponse = consultantService.getConsultantUpdateResponse(user.getEmail());
                return ResponseEntity.ok()
                        .header("X-Custom-Message", "Cập nhật profile thành công tại " + java.time.LocalDateTime.now())
                        .body(consultantUpdateResponse);
            }catch (Exception e) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body("Cập nhật thông tin tư vấn viên thất bại: " + e.getMessage());
            }
        }
            //create user from map body
        Set<String> allowedFields = Set.of("fullName", "yob", "gender", "phone");
        for (String key : body.keySet()) {
            if (!allowedFields.contains(key)) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body("Cập nhật thông tin người dùng thất bại! Làm ơn điền đúng vào body fields được cho phép theo role: "+ user.getRoleName() +" của bro thôi nhé!");
            }
        }
            try {
                UpdateUserProfileRequest userRequest = new UpdateUserProfileRequest();
                userRequest.setFullName((String) body.get("fullName"));
                userRequest.setYob((Integer) body.get("yob"));
                userRequest.setGender((String) body.get("gender"));
                userRequest.setPhone((String) body.get("phone"));

                profileServiceImpl.updateUserProfile(user, userRequest);

                ProfileResponse profileResponse = profileService.getProfile(user.getEmail());
                return ResponseEntity.ok()
                        .header("X-Custom-Message", "Cập nhật profile thành công tại " + java.time.LocalDateTime.now())
                        .body(profileResponse);

            }catch (Exception e) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body("Cập nhật thông tin người dùng thất bại: " + e.getMessage());
            }

    }

}
