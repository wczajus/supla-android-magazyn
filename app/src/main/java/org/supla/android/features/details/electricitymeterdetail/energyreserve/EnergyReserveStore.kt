/* SPDX-License-Identifier: GPL-2.0-or-later */

package org.supla.android.features.details.electricitymeterdetail.energyreserve

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.reactivex.rxjava3.core.Completable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.supla.android.usecases.profile.DeleteProfileUseCase
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EnergyReserveStore @Inject constructor(@ApplicationContext context: Context) : DeleteProfileUseCase.ProfileRemover {
  private val preferences = context.getSharedPreferences("energy_reserve", Context.MODE_PRIVATE)
  private val json = Json { ignoreUnknownKeys = true }

  fun read(profileId: Long, channelId: Int): EnergyReserve? =
    preferences.getString(key(profileId, channelId), null)?.let { json.decodeFromString<EnergyReserve>(it) }

  fun write(reserve: EnergyReserve) {
    check(preferences.edit().putString(key(reserve.profileId, reserve.channelId), json.encodeToString(reserve)).commit())
  }

  fun delete(profileId: Long, channelId: Int) {
    check(preferences.edit().remove(key(profileId, channelId)).commit())
  }

  override fun deleteByProfile(profileId: Long): Completable = Completable.fromAction {
    val editor = preferences.edit()
    preferences.all.keys.filter { it.startsWith("$profileId:") }.forEach { editor.remove(it) }
    check(editor.commit())
  }

  private fun key(profileId: Long, channelId: Int) = "$profileId:$channelId"
}
