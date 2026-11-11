package com.github.yingli.repository;

import com.github.yingli.utils.RedisHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * {@link SessionOwnershipRepository} 的单元测试。
 * <p>用 mock 的 {@link RedisHelper} 覆盖归属登记、越权拒绝、legacy 兼容和按用户列表过滤，
 * 不依赖真实 Redis。</p>
 */
@ExtendWith(MockitoExtension.class)
class SessionOwnershipRepositoryTest {

    private static final String OWNER_KEY = "chat:session:owner:";
    private static final String USER_KEY = "chat:user:sessions:";

    @Mock
    private RedisHelper redisHelper;

    private SessionOwnershipRepository repository;

    @BeforeEach
    void setUp() {
        repository = new SessionOwnershipRepository(redisHelper);
        ReflectionTestUtils.setField(repository, "memoryPrefix", "chat:memory:");
        ReflectionTestUtils.setField(repository, "ttlDays", 7);
    }

    @Test
    void claimOrVerify_shouldClaimOnFirstWrite() {
        when(redisHelper.get(OWNER_KEY + "s1")).thenReturn(null);
        when(redisHelper.setIfAbsent(eq(OWNER_KEY + "s1"), eq("userA"), any(Duration.class))).thenReturn(true);

        assertEquals(SessionOwnershipRepository.ClaimResult.CLAIMED,
                repository.claimOrVerify("s1", "userA"));
        verify(redisHelper).addToSet(eq(USER_KEY + "userA"), eq("s1"), any(Duration.class));
    }

    @Test
    void claimOrVerify_shouldReturnOwnedForSameUser() {
        when(redisHelper.get(OWNER_KEY + "s1")).thenReturn("userA");

        assertEquals(SessionOwnershipRepository.ClaimResult.OWNED,
                repository.claimOrVerify("s1", "userA"));
        verify(redisHelper).addToSet(eq(USER_KEY + "userA"), eq("s1"), any(Duration.class));
    }

    @Test
    void claimOrVerify_shouldForbidOtherUser() {
        when(redisHelper.get(OWNER_KEY + "s1")).thenReturn("userB");

        assertEquals(SessionOwnershipRepository.ClaimResult.FORBIDDEN,
                repository.claimOrVerify("s1", "userA"));
        verify(redisHelper, never()).addToSet(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void claimOrVerify_shouldForbidLegacySessionOfAnotherUser() {
        // legacy:userA 的归属由前缀推导，userB 不得抢占
        assertEquals(SessionOwnershipRepository.ClaimResult.FORBIDDEN,
                repository.claimOrVerify("legacy:userA", "userB"));
        verify(redisHelper, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void claimOrVerify_shouldForbidBlankUser() {
        assertEquals(SessionOwnershipRepository.ClaimResult.FORBIDDEN,
                repository.claimOrVerify("s1", " "));
        verifyNoInteractions(redisHelper);
    }

    @Test
    void isOwner_shouldAllowOwner() {
        when(redisHelper.get(OWNER_KEY + "s1")).thenReturn("userA");
        assertTrue(repository.isOwner("s1", "userA"));
    }

    @Test
    void isOwner_shouldDenyOtherUser() {
        when(redisHelper.get(OWNER_KEY + "s1")).thenReturn("userB");
        assertFalse(repository.isOwner("s1", "userA"));
    }

    @Test
    void isOwner_shouldLazilyClaimLegacySession() {
        when(redisHelper.get(OWNER_KEY + "legacy:userA")).thenReturn(null);

        assertTrue(repository.isOwner("legacy:userA", "userA"));
        verify(redisHelper).addToSet(eq(USER_KEY + "userA"), eq("legacy:userA"), any(Duration.class));
    }

    @Test
    void isOwner_shouldDenyUnownedNonLegacySession() {
        when(redisHelper.get(OWNER_KEY + "s1")).thenReturn(null);
        assertFalse(repository.isOwner("s1", "userA"));
    }

    @Test
    void listSessions_shouldOnlyReturnSessionsOwnedByUser() {
        when(redisHelper.members(USER_KEY + "userA"))
                .thenReturn(new java.util.LinkedHashSet<>(Set.of("s1", "s2")));
        when(redisHelper.get(OWNER_KEY + "s1")).thenReturn("userA");
        when(redisHelper.get(OWNER_KEY + "s2")).thenReturn("userB");
        when(redisHelper.exists("chat:memory:s1")).thenReturn(true);

        List<String> result = repository.listSessions("userA");

        assertEquals(List.of("s1"), new ArrayList<>(result));
        // 归属不属于当前用户的成员被清理出索引
        verify(redisHelper).removeFromSet(USER_KEY + "userA", "s2");
    }

    @Test
    void listSessions_shouldPruneExpiredMemory() {
        when(redisHelper.members(USER_KEY + "userA")).thenReturn(Set.of("s1"));
        when(redisHelper.get(OWNER_KEY + "s1")).thenReturn("userA");
        when(redisHelper.exists("chat:memory:s1")).thenReturn(false);

        List<String> result = repository.listSessions("userA");

        assertTrue(result.isEmpty());
        verify(redisHelper).removeFromSet(USER_KEY + "userA", "s1");
        verify(redisHelper).delete(OWNER_KEY + "s1");
    }

    @Test
    void listSessions_shouldIncludeLegacySessionWhenMemoryExists() {
        when(redisHelper.members(USER_KEY + "userA")).thenReturn(Set.of());
        when(redisHelper.exists("chat:memory:legacy:userA")).thenReturn(true);

        List<String> result = repository.listSessions("userA");

        assertEquals(List.of("legacy:userA"), result);
    }

    @Test
    void remove_shouldDeleteOwnerKeyAndIndexEntry() {
        repository.remove("s1", "userA");

        verify(redisHelper).delete(OWNER_KEY + "s1");
        verify(redisHelper).removeFromSet(USER_KEY + "userA", "s1");
    }
}
