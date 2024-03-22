package com.flowfoundation.wallet.manager.evm

import com.flowfoundation.wallet.R
import com.flowfoundation.wallet.manager.app.chainNetWorkString
import com.flowfoundation.wallet.manager.app.isPreviewnet
import com.flowfoundation.wallet.manager.flowjvm.CADENCE_FUND_COA_FLOW_BALANCE
import com.flowfoundation.wallet.manager.flowjvm.cadenceFundFlowToCOAAccount
import com.flowfoundation.wallet.manager.flowjvm.cadenceQueryEVMAddress
import com.flowfoundation.wallet.manager.flowjvm.cadenceWithdrawTokenFromCOAAccount
import com.flowfoundation.wallet.manager.flowjvm.transactionByMainWallet
import com.flowfoundation.wallet.manager.transaction.TransactionStateWatcher
import com.flowfoundation.wallet.manager.transaction.isExecuteFinished
import com.flowfoundation.wallet.manager.wallet.WalletManager
import com.flowfoundation.wallet.utils.extensions.res2String
import com.flowfoundation.wallet.utils.ioScope
import com.flowfoundation.wallet.utils.logd
import com.flowfoundation.wallet.wallet.removeAddressPrefix
import com.flowfoundation.wallet.wallet.toAddress
import com.google.android.datatransport.runtime.dagger.Reusable
import wallet.core.jni.CoinType
import wallet.core.jni.Curve
import wallet.core.jni.HDWallet

private val TAG = EVMWalletManager::class.java.simpleName

object EVMWalletManager {

    private const val DERIVATION_PATH = "m/44'/60'/1'/0/0"
    private val wallet = HDWallet(128, "")
    private val evmAddressMap = mutableMapOf<String, String>()

    fun wallet(): HDWallet {
        return wallet
    }

    fun address(): String {
        return CoinType.ETHEREUM.deriveAddress(wallet.getKey(CoinType.ETHEREUM, DERIVATION_PATH))
            .lowercase()
    }

    fun signData(data: ByteArray): ByteArray {
        return wallet.getCurveKey(Curve.SECP256K1, DERIVATION_PATH).sign(data, Curve.SECP256K1)
            .apply {
                (this[this.size - 1]) = (this[this.size - 1] + 27).toByte()
            }
    }

    fun showEVMEnablePage(): Boolean {
        return isPreviewnet() && haveEVMAddress().not()
    }

    fun fetchEVMAddress(callback: ((isSuccess: Boolean) -> Unit)? = null) {
        if (!isPreviewnet()) {
            callback?.invoke(false)
        }
        logd(TAG, "fetchEVMAddress()")
        ioScope {
            val address = cadenceQueryEVMAddress()
            val prefixAddress = address?.toAddress()
            logd(TAG, "fetchEVMAddress address::$prefixAddress")
            if (prefixAddress != null) {
                evmAddressMap[chainNetWorkString()] = prefixAddress
                callback?.invoke(true)
            } else {
                callback?.invoke(false)
            }
        }
    }

    fun showEVMAccount(): Boolean {
        return isPreviewnet() && haveEVMAddress()
    }

    fun getEVMAccount(): EVMAccount? {
        val address = getEVMAddress()
        address ?: return null
        return EVMAccount(
            address = address,
            name = R.string.default_evm_account_name.res2String(),
            icon = "https://firebasestorage.googleapis.com/v0/b/lilico-334404.appspot" +
                    ".com/o/asset%2Feth.png?alt=media&token=1b926945-5459-4aee-b8ef-188a9b4acade",
        )
    }

    fun haveEVMAddress(): Boolean {
        return getEVMAddress().isNullOrBlank().not()
    }

    fun getEVMAddress(): String? {
        return evmAddressMap[chainNetWorkString()]
    }

    fun isEVMWalletAddress(address: String): Boolean {
        return evmAddressMap.values.firstOrNull { it == address } != null
    }

    fun clear() {
        evmAddressMap.clear()
    }

    suspend fun moveFlowToken(amount: Float, isFundToEVM: Boolean, callback: (isSuccess: Boolean) -> Unit) {
        if (isFundToEVM) {
            fundFlowToEVM(amount, callback)
        } else {
            withdrawFlowFromEVM(amount, callback)
        }
    }

    suspend fun fundFlowToEVM(amount: Float, callback: (isSuccess: Boolean) -> Unit) {
        try {
            val txId = cadenceFundFlowToCOAAccount(amount)
            if (txId.isNullOrBlank()) {
                logd(TAG, "fund flow to evm failed")
                callback.invoke(false)
                return
            }
            TransactionStateWatcher(txId).watch { result ->
                if (result.isExecuteFinished()) {
                    logd(TAG, "fund flow to evm success")
                    callback.invoke(true)
                }
            }
        } catch (e: Exception) {
            callback.invoke(false)
            logd(TAG, "fund flow to evm failed")
            e.printStackTrace()
        }
    }

    suspend fun withdrawFlowFromEVM(amount: Float, callback: (isSuccess: Boolean) -> Unit) {
        try {
            val toAddress = WalletManager.wallet()?.walletAddress() ?: return callback.invoke(false)
            val txId = cadenceWithdrawTokenFromCOAAccount(amount, toAddress)
            if (txId.isNullOrBlank()) {
                logd(TAG, "withdraw flow from evm failed")
                callback.invoke(false)
                return
            }
            TransactionStateWatcher(txId).watch { result ->
                if (result.isExecuteFinished()) {
                    logd(TAG, "withdraw flow from evm success")
                    callback.invoke(true)
                }
            }

        } catch (e: Exception) {
            callback.invoke(false)
            logd(TAG, "withdraw flow from evm failed")
            e.printStackTrace()
        }
    }

}