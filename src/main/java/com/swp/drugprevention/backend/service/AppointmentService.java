package com.swp.drugprevention.backend.service;

import com.swp.drugprevention.backend.enums.ConsultationStatus;
import com.swp.drugprevention.backend.io.request.AppointmentRequest;
import com.swp.drugprevention.backend.io.response.AppointmentResponse;
import com.swp.drugprevention.backend.model.Appointment;
import com.swp.drugprevention.backend.model.Consultant;
import com.swp.drugprevention.backend.model.User;
import com.swp.drugprevention.backend.repository.AppointmentRepository;
import com.swp.drugprevention.backend.repository.ConsultantRepository;
import com.swp.drugprevention.backend.repository.UserRepository;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class AppointmentService {
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private AppointmentRepository appointmentRepository;
    @Autowired
    private ConsultantRepository consultantRepository;
    private EmailService emailService;

    public List<AppointmentResponse> getAllAppointments() {
        List<Appointment> appointments = appointmentRepository.findAll();
        return appointments.stream()
                .map(appointment -> new AppointmentResponse(
                        appointment.getAppointmentId(),
                        appointment.getDate(),
                        appointment.getStartTime(),
                        appointment.getEndTime(),
                        appointment.getStatus(),
                        appointment.getLocation(),
                        appointment.getUser() != null ? appointment.getUser().getUserId() : null,
                        appointment.getConsultant() != null ? appointment.getConsultant().getConsultantId() : null
                ))
                .collect(Collectors.toList());
    }

    public AppointmentResponse saveAppointment(@Valid AppointmentRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request cannot be null");
        }

        LocalDate date = request.getDate();
        LocalTime startTime = request.getStartTime();
        LocalTime endTime = startTime.plusHours(2);

        // Check 1 ngày 1 user chỉ được đặt 2 cuộc hẹn với bất kỳ consultant nào
        Integer userId = request.getUserId();
        List<Appointment> userAppointments = appointmentRepository.findByUserUserIdAndDate(userId, date);
        if (userAppointments.size() >= 2) {
            throw new IllegalStateException("Bạn chỉ được đặt tối đa 2 cuộc hẹn trong một ngày. Xin cảm ơn bạn đã hiểu và thông cảm.");
        }

        // Kiểm tra xem consultant có tồn tại hay không và thời gian có đang trống, phía dưới có kiểm tra xung đột thời gian
        // ở isSlotAvailable
        if (!isSlotAvailable(request.getConsultantId(), date, startTime)) {
            throw new IllegalStateException("Thời gian này đã được đặt hoặc gần với cuộc hẹn khác. Vui lòng chọn khung giờ khác.");
        }

        Consultant consultant = consultantRepository.findById(request.getConsultantId())
                .orElseThrow(() -> new RuntimeException("Consultant not found with ID: " + request.getConsultantId()));

        // Tạo appointment
        Appointment appointment = new Appointment();
        appointment.setDate(date);
        appointment.setStartTime(startTime);
        appointment.setEndTime(endTime);
        appointment.setStatus(ConsultationStatus.Pending);
        appointment.setLocation(request.getLocation());

        User user = userRepository.findById(request.getUserId())
                .orElseThrow(() -> new RuntimeException("User not found with ID: " + request.getUserId()));
        appointment.setUser(user);
        appointment.setConsultant(consultant);

        Appointment savedAppointment = appointmentRepository.save(appointment);
        return new AppointmentResponse(
                savedAppointment.getAppointmentId(),
                savedAppointment.getDate(),
                savedAppointment.getStartTime(),
                savedAppointment.getEndTime(),
                savedAppointment.getStatus(),
                savedAppointment.getLocation(),
                request.getUserId(),
                request.getConsultantId()
        );
    }

    public boolean isSlotAvailable(Integer consultantId, LocalDate date, LocalTime startTime) {
        Consultant consultant = consultantRepository.findById(consultantId)
                .orElseThrow(() -> new RuntimeException("Consultant not found with ID: " + consultantId));

        LocalTime endTime = startTime.plusHours(2); //mỗi cuộc hẹn kéo dài 2 giờ

        // Lấy tất cả cuộc hẹn của consultant trong ngày
        List<Appointment> existingAppointments = appointmentRepository.findByConsultantAndDate(consultant, date);

        // Kiểm tra xung đột thời gian (gần hơn 30 phút)
        // Nghĩa là nếu cuộc hẹn mới bắt đầu trong khoảng 30 phút trước hoặc sau cuộc hẹn hiện tại, thì sẽ coi là xung đột
        // Ví dụ : nếu cuộc hẹn hiện tại bắt đầu lúc 10:00 và kết thúc lúc 12:00,
        // thì cuộc hẹn mới không được bắt đầu trước 09:30 và không được kết thúc sau 12:30
        for (Appointment appt : existingAppointments) {
            LocalTime existingStart = appt.getStartTime();
            LocalTime existingEnd = appt.getEndTime();

            if (!(endTime.plusMinutes(30).isBefore(existingStart) || startTime.minusMinutes(30).isAfter(existingEnd))) {
                return false; // Có xung đột, trả về false
            }
        }

        return true; // Không có xung đột, trả về true
    }

    public Appointment getAppointmentById(Integer id) {
        return appointmentRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Appointment not found"));
    }

    public AppointmentResponse updateAppointment(Integer id, AppointmentRequest request) {
        Appointment appointment = getAppointmentById(id);
        appointment.setStartTime(request.getStartTime());
        appointment.setEndTime(request.getEndTime());
        appointment.setDate(request.getDate());
        appointment.setLocation(request.getLocation());
        User user = userRepository.findById(request.getUserId())
                .orElseThrow(() -> new RuntimeException("User not found with ID: " + request.getUserId()));
        appointment.setUser(user);

        Consultant consultant = consultantRepository.findById(request.getConsultantId())
                .orElseThrow(() -> new RuntimeException("Consultant not found with ID: " + request.getConsultantId()));
        appointment.setConsultant(consultant);

        Appointment savedAppointment = appointmentRepository.save(appointment);
        return new AppointmentResponse(
                savedAppointment.getAppointmentId(),
                savedAppointment.getDate(),
                savedAppointment.getStartTime(),
                savedAppointment.getEndTime(),
                savedAppointment.getStatus(),
                savedAppointment.getLocation(),
                request.getUserId(),
                request.getConsultantId()
        );
    }

    public void deleteAppointment(Integer id) {
        appointmentRepository.deleteById(id);
    }

    public List<AppointmentResponse> getMyAppointments(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found with email: " + email));

        LocalDate today = LocalDate.now();

        List<Appointment> appointments = appointmentRepository
                .findByUserAndDateGreaterThanEqualOrderByDateAscStartTimeAsc(user, today);

        return appointments.stream()
                .map(appointment -> new AppointmentResponse(
                        appointment.getAppointmentId(),
                        appointment.getDate(),
                        appointment.getStartTime(),
                        appointment.getEndTime(),
                        appointment.getStatus(),
                        appointment.getLocation(),
                        user.getUserId(),
                        appointment.getConsultant() != null ? appointment.getConsultant().getConsultantId() : null
                ))
                .collect(Collectors.toList());
    }

    public AppointmentResponse cancelAppointment(Integer appointmentId) {
        Appointment appointment = getAppointmentById(appointmentId);
        if (appointment.getStatus() == ConsultationStatus.Cancel || appointment.getStatus() == ConsultationStatus.Completed) {
            throw new IllegalStateException("Cuộc hẹn đã bị hủy trước đó.");
        }
        appointment.setStatus(ConsultationStatus.Cancel);
        Appointment updatedAppointment = appointmentRepository.save(appointment);
        return new AppointmentResponse(
                updatedAppointment.getAppointmentId(),
                updatedAppointment.getDate(),
                updatedAppointment.getStartTime(),
                updatedAppointment.getEndTime(),
                updatedAppointment.getStatus(),
                updatedAppointment.getLocation(),
                updatedAppointment.getUser() != null ? updatedAppointment.getUser().getUserId() : null,
                updatedAppointment.getConsultant() != null ? updatedAppointment.getConsultant().getConsultantId() : null
        );
    }

//    @Scheduled(fixedRate = 30000) // Chạy mỗi 30 giây
//    public void autoUpdateCancelledAppointments() {
//        LocalDateTime currentDateTime = LocalDateTime.now(); // Sử dụng LocalDateTime thay vì LocalTime
//        List<Appointment> activeAppointments = appointmentRepository.findByStatusNot(ConsultationStatus.Cancel);
//
//        for (Appointment appointment : activeAppointments) {
//            LocalDateTime appointmentEndDateTime = LocalDateTime.of(appointment.getDate(), appointment.getEndTime());
//            if (currentDateTime.isAfter(appointmentEndDateTime)) {
//                appointment.setStatus(ConsultationStatus.Cancel);
//                appointmentRepository.save(appointment);
//            }
//        }
//    }

    public List<AppointmentResponse> getAppointmentsByConsultant(Integer consultantId) {
        Consultant consultant = consultantRepository.findById(consultantId)
                .orElseThrow(() -> new RuntimeException("Consultant not found with ID: " + consultantId));

        List<Appointment> appointments = appointmentRepository.findByConsultant(consultant);

        return appointments.stream()
                .map(appointment -> new AppointmentResponse(
                        appointment.getAppointmentId(),
                        appointment.getDate(),
                        appointment.getStartTime(),
                        appointment.getEndTime(),
                        appointment.getStatus(),
                        appointment.getLocation(),
                        appointment.getUser() != null ? appointment.getUser().getUserId() : null,
                        consultant.getConsultantId()
                ))
                .collect(Collectors.toList());
    }

    public AppointmentResponse setStatus(Integer appointmentId, ConsultationStatus newStatus) {
        Appointment appointment = appointmentRepository.findById(appointmentId)
                .orElseThrow(() -> new RuntimeException("Appointment not found with ID: " + appointmentId));

        // Chỉ update nếu status hợp lệ (tuỳ luật lệ của bạn)
        if (appointment.getStatus() == ConsultationStatus.Cancel) {
            throw new IllegalStateException("Cuộc hẹn đã bị hủy, không thể thay đổi trạng thái.");
        }

        appointment.setStatus(newStatus);
        Appointment updated = appointmentRepository.save(appointment);

        return new AppointmentResponse(
                updated.getAppointmentId(),
                updated.getDate(),
                updated.getStartTime(),
                updated.getEndTime(),
                updated.getStatus(),
                updated.getLocation(),
                updated.getUser() != null ? updated.getUser().getUserId() : null,
                updated.getConsultant() != null ? updated.getConsultant().getConsultantId() : null
        );
    }
}
//    @Scheduled(fixedRate = 60000) // Chạy mỗi 1 phút (60.000 ms)
//    public void sendAppointmentReminders() {
//        List<Appointment> upcomingAppointments = appointmentRepository.findByStatus(ConsultationStatus.Pending);
//
//        LocalDateTime now = LocalDateTime.now();
//
//        for (Appointment appointment : upcomingAppointments) {
//            LocalDateTime appointmentDateTime = LocalDateTime.of(appointment.getDate(), appointment.getStartTime());
//            LocalDateTime reminderTime = appointmentDateTime.minusHours(12);
//
//            if (now.isAfter(reminderTime) && now.isBefore(reminderTime.plusMinutes(5))) {
//                String to = appointment.getUser().getEmail();
//                String name = appointment.getUser().getFullName(); // thay bằng đúng field
//                emailService.sendAppointmentReminder(to, name, appointmentDateTime);
//            }
//        }
//    }
