package com.whatsappscheduler.whatsapp_scheduler;

import java.sql.DriverManager;
import java.time.*;
import java.util.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpSession;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import tools.jackson.databind.json.JsonMapper;
import com.whatsappscheduler.whatsapp_scheduler.agendamento.*;
import com.whatsappscheduler.whatsapp_scheduler.execucao.*;
import com.whatsappscheduler.whatsapp_scheduler.grupo.*;
import com.whatsappscheduler.whatsapp_scheduler.whatsapp.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(WhatsappSchedulerApplicationTests.TestClockConfig.class)
@EnabledIfEnvironmentVariable(named = "TEST_POSTGRES_URL", matches = ".+")
class WhatsappSchedulerApplicationTests {
    private static final String SCHEMA = "scheduler_test_" + UUID.randomUUID().toString().replace("-", "");
    private static final MutableClock CLOCK = new MutableClock();

    private static String url() {
        return System.getenv("TEST_POSTGRES_URL");
    }

    private static String username() {
        return System.getenv().getOrDefault("TEST_POSTGRES_USERNAME", "postgres");
    }

    private static String password() {
        return System.getenv().getOrDefault("TEST_POSTGRES_PASSWORD", "postgres");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        try (var c = DriverManager.getConnection(url(), username(), password()); var s = c.createStatement()) {
            s.execute("CREATE SCHEMA " + SCHEMA);
        }
        registry.add("spring.datasource.url", () -> url() + (url().contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA);
        registry.add("spring.datasource.username", WhatsappSchedulerApplicationTests::username);
        registry.add("spring.datasource.password", WhatsappSchedulerApplicationTests::password);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("app.scheduler.enabled", () -> false);
        registry.add("app.admin.password", () -> "test-admin-password-123");
        registry.add("app.worker.token", () -> "test-worker-token-with-more-than-32-characters");
        registry.add("logging.file.name", () -> "/tmp/whatsapp-scheduler-integration.log");
    }

    @AfterAll
    static void cleanupSchema() throws Exception {
        try (var c = DriverManager.getConnection(url(), username(), password()); var s = c.createStatement()) {
            s.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }

    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-02T12:00:00Z");

        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId zone) {
            return this;
        }

        public Instant instant() {
            return now;
        }
    }

    @TestConfiguration
    static class TestClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return CLOCK;
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    AgendamentoService schedules;
    @Autowired
    AgendamentoRepository scheduleRepository;
    @Autowired
    ExecucaoService executions;
    @Autowired
    ExecucaoRepository executionRepository;
    @Autowired
    GrupoRepository groups;
    @Autowired
    GrupoService groupService;
    @MockitoBean
    WorkerClient worker;
    private final JsonMapper json = JsonMapper.builder().build();
    private Grupo group;

    @BeforeEach
    void setup() {
        executionRepository.deleteAll();
        scheduleRepository.deleteAll();
        groups.deleteAll();
        CLOCK.now = Instant.parse("2026-11-02T12:00:00Z");
        group = new Grupo();
        group.whatsappId = "123456@g.us";
        group.nome = "Teste";
        group.ativo = true;
        group.sincronizadoEm = CLOCK.instant();
        groups.saveAndFlush(group);
        when(worker.send(anyString(), anyString(), anyString(), any())).thenAnswer(call -> new WorkerClient.Sent(call.getArgument(0), "sent", "message-1", false));
    }

    private AgendamentoRequest request(LocalTime time, LocalDate end) {
        return new AgendamentoRequest(group.id, "Mensagem teste", Frequencia.DIARIA, time, null, null, false,
                LocalDate.of(2026, 11, 1), end, "America/Sao_Paulo");
    }

    private Agendamento due() {
        var a = schedules.create(request(LocalTime.of(9, 0), null));
        assertEquals(CLOCK.instant(), a.proximaExecucaoEm);
        return a;
    }

    private void tick() {
        new AgendamentoScheduler(executions, worker).tick();
    }

    private Execucao onlyExecution() {
        assertEquals(1, executionRepository.count());
        return executionRepository.findAll().getFirst();
    }

    @Test
    void loginRotatesSessionAndLogoutInvalidatesIt() throws Exception {
        mvc.perform(get("/api/grupos")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType("application/json").content("{\"login\":\"admin\",\"senha\":\"test-admin-password-123\"}")).andExpect(status().isForbidden());
        var challenge = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
        var oldSession = (MockHttpSession) challenge.getRequest().getSession();
        String token = json.readTree(challenge.getResponse().getContentAsString()).path("token").asString();
        var login = mvc.perform(post("/api/auth/login").session(oldSession).header("X-CSRF-TOKEN", token).contentType("application/json")
                .content("{\"login\":\"admin\",\"senha\":\"test-admin-password-123\"}")).andExpect(status().isOk()).andReturn();
        var session = (MockHttpSession) login.getRequest().getSession();
        assertNotEquals(oldSession.getId(), session.getId());
        assertTrue(oldSession.isInvalid());
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.login").value("admin"));
        var renewed = mvc.perform(get("/api/auth/csrf").session(session)).andExpect(status().isOk()).andReturn();
        String renewedToken = json.readTree(renewed.getResponse().getContentAsString()).path("token").asString();
        mvc.perform(post("/api/auth/logout").session(session).header("X-CSRF-TOKEN", renewedToken)).andExpect(status().isNoContent());
        assertTrue(session.isInvalid());
    }

    @Test
    void invalidCredentialsAreRejected() throws Exception {
        mvc.perform(post("/api/auth/login").with(csrf()).contentType("application/json").content("{\"login\":\"admin\",\"senha\":\"errada\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void apiValidatesRecurrenceAndListsSchedules() throws Exception {
        String body = json.writeValueAsString(request(LocalTime.of(10, 0), null));
        mvc.perform(post("/api/agendamentos").with(user("admin")).with(csrf()).contentType("application/json").content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.frequencia").value("DIARIA"));
        mvc.perform(get("/api/agendamentos").with(user("admin"))).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/agendamentos?tamanho=101").with(user("admin"))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/agendamentos").with(user("admin")).with(csrf()).contentType("application/json").content(body.replace("DIARIA", "SEMANAL")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sendsOnceAndAdvancesRecurrence() {
        var a = due();
        tick();
        tick();
        assertEquals(StatusExecucao.ENVIADO, onlyExecution().status);
        verify(worker, times(1)).send(anyString(), eq(group.whatsappId), eq("Mensagem teste"), any());
        assertEquals(Instant.parse("2026-11-03T12:00:00Z"), scheduleRepository.findById(a.id).orElseThrow().proximaExecucaoEm);
    }

    @Test
    void exactlyThirtyMinutesIsAllowed() {
        due();
        CLOCK.now = CLOCK.now.plusSeconds(1800);
        tick();
        assertEquals(StatusExecucao.ENVIADO, onlyExecution().status);
    }

    @Test
    void overdueOccurrenceExpiresAndBacklogIsSkipped() {
        var a = due();
        CLOCK.now = Instant.parse("2026-12-02T12:31:00Z");
        tick();
        tick();
        assertEquals(StatusExecucao.EXPIRADO, onlyExecution().status);
        verifyNoInteractions(worker);
        assertEquals(Instant.parse("2026-12-03T12:00:00Z"), scheduleRepository.findById(a.id).orElseThrow().proximaExecucaoEm);
    }

    @Test
    void validityEndPreventsLateSendAndDuplicateMaterialization() {
        CLOCK.now = Instant.parse("2026-11-03T02:59:00Z");
        var a = schedules.create(request(LocalTime.of(23, 59), LocalDate.of(2026, 11, 2)));
        CLOCK.now = Instant.parse("2026-11-03T03:01:00Z");
        tick();
        tick();
        assertEquals(StatusExecucao.EXPIRADO, onlyExecution().status);
        verifyNoInteractions(worker);
        assertNull(scheduleRepository.findById(a.id).orElseThrow().proximaExecucaoEm);
    }

    @Test
    void interruptedExecutionRecoversWithSameIdAfterLease() {
        due();
        executions.materialize();
        var first = executions.claim();
        tick();
        verifyNoInteractions(worker);
        CLOCK.now = CLOCK.now.plusSeconds(121);
        tick();
        verify(worker).send(first.id.toString(), first.grupoWhatsappId, first.texto, first.limiteEnvioEm);
        assertEquals(StatusExecucao.ENVIADO, onlyExecution().status);
    }

    @Test
    void lostResponseRetriesSameIdAndContent() {
        due();
        when(worker.send(anyString(), anyString(), anyString(), any())).thenThrow(new WorkerException(503, "TRANSPORT_ERROR"))
                .thenAnswer(call -> new WorkerClient.Sent(call.getArgument(0), "sent", "already-sent", true));
        tick();
        var first = onlyExecution();
        assertEquals(StatusExecucao.AGUARDANDO_NOVA_TENTATIVA, first.status);
        CLOCK.now = CLOCK.now.plusSeconds(31);
        tick();
        verify(worker, times(2)).send(first.id.toString(), first.grupoWhatsappId, first.texto, first.limiteEnvioEm);
        assertEquals(StatusExecucao.ENVIADO, onlyExecution().status);
    }

    @Test
    void uncertainResultIsNeverRetried() {
        due();
        when(worker.send(anyString(), anyString(), anyString(), any())).thenThrow(new WorkerException(409, "SEND_UNCERTAIN"));
        tick();
        CLOCK.now = CLOCK.now.plusSeconds(121);
        tick();
        assertEquals(StatusExecucao.INCERTO, onlyExecution().status);
        verify(worker, times(1)).send(anyString(), anyString(), anyString(), any());
    }

    @Test
    void interruptedExecutionOutsideWindowRemainsUncertain() {
        due();
        executions.materialize();
        executions.claim();
        CLOCK.now = CLOCK.now.plusSeconds(1801);
        tick();
        assertEquals(StatusExecucao.INCERTO, onlyExecution().status);
        verifyNoInteractions(worker);
    }

    @Test
    void safeFailureRetriesButPermissionFailureIsFinal() {
        due();
        when(worker.send(anyString(), anyString(), anyString(), any())).thenThrow(new WorkerException(503, "NOT_CONNECTED"))
                .thenThrow(new WorkerException(403, "GROUP_RESTRICTED"));
        tick();
        assertEquals(StatusExecucao.AGUARDANDO_NOVA_TENTATIVA, onlyExecution().status);
        CLOCK.now = CLOCK.now.plusSeconds(31);
        tick();
        assertEquals(StatusExecucao.FALHOU, onlyExecution().status);
    }

    @Test
    void pauseCancelsPendingAndResumeSkipsIt() {
        var a = due();
        executions.materialize();
        schedules.changeStatus(a.id, StatusAgendamento.PAUSADO);
        tick();
        assertEquals(StatusExecucao.CANCELADO, onlyExecution().status);
        verifyNoInteractions(worker);
        a = schedules.changeStatus(a.id, StatusAgendamento.ATIVO);
        assertEquals(Instant.parse("2026-11-03T12:00:00Z"), a.proximaExecucaoEm);
        schedules.changeStatus(a.id, StatusAgendamento.CANCELADO);
        Long id = a.id;
        assertThrows(RuntimeException.class, () -> schedules.changeStatus(id, StatusAgendamento.ATIVO));
    }

    @Test
    void editingInFlightIsRejected() {
        var a = due();
        executions.materialize();
        executions.claim();
        assertThrows(RuntimeException.class, () -> schedules.update(a.id, request(LocalTime.of(10, 0), null)));
        assertEquals("Mensagem teste", onlyExecution().texto);
    }

    @Test
    void syncUpdatesGroupsAndPreservesThemOnFailure() {
        when(worker.groups()).thenReturn(List.of(new WorkerClient.Group("999@g.us", "Novo grupo", true)));
        assertEquals(1, groupService.sync());
        assertFalse(groups.findById(group.id).orElseThrow().ativo);
        assertTrue(groups.findByWhatsappId("999@g.us").orElseThrow().ativo);
        when(worker.groups()).thenThrow(new WorkerException(503, "TRANSPORT_ERROR"));
        assertThrows(WorkerException.class, groupService::sync);
        assertTrue(groups.findByWhatsappId("999@g.us").orElseThrow().ativo);
    }

    @Test
    void validEditUpdatesNextOccurrenceAndMonthlyPayloadIsAccepted() throws Exception {
        var a = schedules.create(request(LocalTime.of(10, 0), null));
        var edited = schedules.update(a.id, request(LocalTime.of(11, 0), LocalDate.of(2026, 12, 1)));
        assertEquals(Instant.parse("2026-11-02T14:00:00Z"), edited.proximaExecucaoEm);
        var monthly = new AgendamentoRequest(group.id, "Fechamento", Frequencia.MENSAL, LocalTime.of(18, 0), null, null, true,
                LocalDate.of(2026, 11, 1), null, "America/Sao_Paulo");
        mvc.perform(post("/api/agendamentos").with(user("admin")).with(csrf()).contentType("application/json").content(json.writeValueAsString(monthly)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.proximaExecucaoEm").value("2026-11-30T21:00:00Z"));
    }

    @Test
    void invalidValidityZoneAndOversizedEncodedPayloadAreRejected() {
        assertThrows(RuntimeException.class, () -> schedules.create(request(LocalTime.of(9, 0), LocalDate.of(2026, 10, 31))));
        var invalidZone = new AgendamentoRequest(group.id, "Mensagem", Frequencia.DIARIA, LocalTime.of(9, 0), null, null, false,
                LocalDate.of(2026, 11, 1), null, "Invalid/Zone");
        assertThrows(RuntimeException.class, () -> schedules.create(invalidZone));
        // 6 bytes JSON por caractere de controle: cabe no limite de caracteres, mas não no limite do worker.
        var oversized = new AgendamentoRequest(group.id, "X" + "\u0001".repeat(9000), Frequencia.DIARIA, LocalTime.of(9, 0), null, null, false,
                LocalDate.of(2026, 11, 1), null, "America/Sao_Paulo");
        assertThrows(RuntimeException.class, () -> schedules.create(oversized));
    }

    @Test
    void workerDeadlineExpirationIsRecordedWithoutRetry() {
        due();
        when(worker.send(anyString(), anyString(), anyString(), any())).thenThrow(new WorkerException(410, "SEND_EXPIRED"));
        tick();
        tick();
        assertEquals(StatusExecucao.EXPIRADO, onlyExecution().status);
        verify(worker, times(1)).send(anyString(), anyString(), anyString(), any());
    }

    @Test
    void repeatedInterruptionsAlsoRespectAttemptLimit() {
        due();
        executions.materialize();
        for (int i = 0; i < 5; i++) {
            assertEquals(StatusExecucao.EM_EXECUCAO, executions.claim().status);
            CLOCK.now = CLOCK.now.plusSeconds(121);
        }
        tick();
        assertEquals(StatusExecucao.INCERTO, onlyExecution().status);
        assertEquals(5, onlyExecution().tentativas);
        verifyNoInteractions(worker);
    }

    @Test
    void minimalDailyBodyDoesNotRequireMonthlyFlag() throws Exception {
        String body = """
                {"grupoId":%d,"texto":"Mensagem automática diária!","frequencia":"DIARIA",
                 "horario":"14:25","inicioVigencia":"2026-11-02","fusoHorario":"America/Sao_Paulo"}
                """.formatted(group.id);
        mvc.perform(post("/api/agendamentos").with(user("admin")).with(csrf()).contentType("application/json").content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.ultimoDiaMes").value(false));
    }

    @Test
    void weeklyExampleWithoutMonthlyFlagIsAccepted() throws Exception {
        String body = """
                {"grupoId":%d,"texto":"Lembrete da programação!","frequencia":"SEMANAL",
                 "diaSemana":"SEXTA","horario":"18:00","inicioVigencia":"2026-11-01",
                 "fimVigencia":"2026-11-30","fusoHorario":"America/Sao_Paulo"}
                """.formatted(group.id);
        mvc.perform(post("/api/agendamentos").with(user("admin")).with(csrf()).contentType("application/json").content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.ultimoDiaMes").value(false))
                .andExpect(jsonPath("$.proximaExecucaoEm").value("2026-11-06T21:00:00Z"));
        mvc.perform(post("/api/agendamentos").with(user("admin")).with(csrf()).contentType("application/json").content(body.replace("Lembrete da programação!", "")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.texto").exists());
    }

    @Test
    void historyUsesGetAndReturnsEmptyThenSavedExecutions() throws Exception {
        var a = due();
        String path = "/api/agendamentos/" + a.id + "/execucoes";
        mvc.perform(get(path).with(user("admin"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        tick();
        mvc.perform(get(path).with(user("admin"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].status").value("ENVIADO"))
                .andExpect(jsonPath("$.content[0].mensagemWhatsappId").value("message-1"));
        mvc.perform(post(path).with(user("admin")).with(csrf())).andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", org.hamcrest.Matchers.containsString("GET")))
                .andExpect(jsonPath("$.code").value("METODO_NAO_PERMITIDO"));
        mvc.perform(get(path + "?tamanho=101").with(user("admin"))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/agendamentos/9223372036854775807/execucoes").with(user("admin"))).andExpect(status().isNotFound());
    }
}
