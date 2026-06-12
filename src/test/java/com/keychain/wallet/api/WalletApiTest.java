package com.keychain.wallet.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP contract tests: status codes, problem details, and response shapes. */
@SpringBootTest
@AutoConfigureMockMvc
class WalletApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String createWallet() throws Exception {
        MvcResult result = mockMvc.perform(post("/wallets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"cust-api\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.balancePaise").value(0))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.get("walletId").asText();
    }

    private void topup(String walletId, long amountPaise) throws Exception {
        mockMvc.perform(post("/wallets/" + walletId + "/topup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountPaise\":" + amountPaise + "}"))
                .andExpect(status().isOk());
    }

    @Test
    void createTopupDeductBalanceHappyPath() throws Exception {
        String walletId = createWallet();
        topup(walletId, 30_000L);

        mockMvc.perform(post("/wallets/" + walletId + "/deduct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"ORD-API-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("DEDUCT"))
                .andExpect(jsonPath("$.amountPaise").value(10_000))
                .andExpect(jsonPath("$.balanceAfterPaise").value(20_000))
                .andExpect(jsonPath("$.replayed").value(false));

        mockMvc.perform(get("/wallets/" + walletId + "/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balancePaise").value(20_000));
    }

    @Test
    void deductRetryReturnsReplayedResult() throws Exception {
        String walletId = createWallet();
        topup(walletId, 30_000L);

        String deduct = "{\"orderId\":\"ORD-API-RETRY\"}";
        mockMvc.perform(post("/wallets/" + walletId + "/deduct")
                        .contentType(MediaType.APPLICATION_JSON).content(deduct))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(false));

        mockMvc.perform(post("/wallets/" + walletId + "/deduct")
                        .contentType(MediaType.APPLICATION_JSON).content(deduct))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.balanceAfterPaise").value(20_000));
    }

    @Test
    void insufficientBalanceReturns422ProblemDetail() throws Exception {
        String walletId = createWallet();

        mockMvc.perform(post("/wallets/" + walletId + "/deduct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"ORD-API-POOR\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("Insufficient balance"))
                .andExpect(jsonPath("$.status").value(422));
    }

    @Test
    void idempotencyKeyReuseWithDifferentPayloadReturns409() throws Exception {
        String walletId = createWallet();

        mockMvc.perform(post("/wallets/" + walletId + "/topup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountPaise\":10000,\"idempotencyKey\":\"K1\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/wallets/" + walletId + "/topup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountPaise\":20000,\"idempotencyKey\":\"K1\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Idempotency conflict"));
    }

    @Test
    void unknownWalletReturns404() throws Exception {
        mockMvc.perform(get("/wallets/00000000-0000-0000-0000-000000000000/balance"))
                .andExpect(status().isNotFound());
    }

    @Test
    void invalidRequestsReturn400() throws Exception {
        String walletId = createWallet();

        mockMvc.perform(post("/wallets/" + walletId + "/topup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountPaise\":-1}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/wallets/" + walletId + "/deduct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/wallets/not-a-uuid/balance"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void transactionHistoryIsNewestFirstWithPaging() throws Exception {
        String walletId = createWallet();
        topup(walletId, 30_000L);
        mockMvc.perform(post("/wallets/" + walletId + "/deduct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"ORD-API-HIST\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/wallets/" + walletId + "/transactions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items[0].type").value("DEDUCT"))
                .andExpect(jsonPath("$.items[1].type").value("TOPUP"));

        mockMvc.perform(get("/wallets/" + walletId + "/transactions?page=0&size=1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.totalPages").value(2));
    }
}
