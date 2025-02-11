package com.flowfoundation.wallet.manager.key

import com.flowfoundation.wallet.manager.account.Account
import com.flowfoundation.wallet.manager.account.AccountManager
import com.flowfoundation.wallet.manager.account.AccountWalletManager
import com.flowfoundation.wallet.wallet.Wallet
import io.outblock.wallet.CryptoProvider
import io.outblock.wallet.KeyStoreCryptoProvider


object CryptoProviderManager {

    private var cryptoProvider: CryptoProvider? = null

    fun getCurrentCryptoProvider(): CryptoProvider? {
        if (cryptoProvider == null) {
            cryptoProvider = generateAccountCryptoProvider(AccountManager.get())
        }
        return cryptoProvider
    }

    fun generateAccountCryptoProvider(account: Account?): CryptoProvider? {
        if (account == null) {
            return null
        }
        if (account.prefix.isNullOrBlank()) {
            val wallet = if (account.isActive) {
                Wallet.store().wallet()
            } else {
                AccountWalletManager.getHDWalletByUID(account.wallet?.id ?: "")
            }
            if (wallet == null) {
                return null
            }
            return HDWalletCryptoProvider(wallet)
        } else {
            return KeyStoreCryptoProvider(account.prefix!!)
        }
    }

    fun isHDWalletCrypto(): Boolean {
        return cryptoProvider != null && cryptoProvider is HDWalletCryptoProvider
    }

    fun clear() {
        cryptoProvider = null
    }
}