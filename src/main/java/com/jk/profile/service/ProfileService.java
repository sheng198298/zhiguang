package com.jk.profile.service;

import com.jk.profile.api.dto.ProfilePatchRequest;
import com.jk.profile.api.dto.ProfileResponse;
import com.jk.user.domain.User;

import java.util.Optional;

/**
 * 个人资料业务接口。
 */
public interface ProfileService {

    Optional<User> getById(long userId);

    ProfileResponse updateProfile(long userId, ProfilePatchRequest req);

    ProfileResponse updateAvatar(long userId, String avatarUrl);
}