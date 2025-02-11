package com.flowfoundation.wallet.page.restore.multirestore.viewmodel

import android.widget.Toast
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
import com.nftco.flow.sdk.FlowTransactionStatus
import com.nftco.flow.sdk.HashAlgorithm
import com.nftco.flow.sdk.SignatureAlgorithm
import com.flowfoundation.wallet.R
import com.flowfoundation.wallet.base.activity.BaseActivity
import com.flowfoundation.wallet.firebase.auth.getFirebaseJwt
import com.flowfoundation.wallet.manager.account.Account
import com.flowfoundation.wallet.manager.account.AccountManager
import com.flowfoundation.wallet.manager.account.DeviceInfoManager
import com.flowfoundation.wallet.manager.backup.BackupCryptoProvider
import com.flowfoundation.wallet.manager.flowjvm.CADENCE_ADD_PUBLIC_KEY
import com.flowfoundation.wallet.manager.flowjvm.CadenceArgumentsBuilder
import com.flowfoundation.wallet.manager.flowjvm.transaction.sendTransactionWithMultiSignature
import com.flowfoundation.wallet.manager.flowjvm.ufix64Safe
import com.flowfoundation.wallet.manager.transaction.OnTransactionStateChange
import com.flowfoundation.wallet.manager.transaction.TransactionState
import com.flowfoundation.wallet.manager.transaction.TransactionStateManager
import com.flowfoundation.wallet.manager.wallet.WalletManager
import com.flowfoundation.wallet.network.ApiService
import com.flowfoundation.wallet.network.clearUserCache
import com.flowfoundation.wallet.network.generatePrefix
import com.flowfoundation.wallet.network.model.AccountKey
import com.flowfoundation.wallet.network.model.AccountKeySignature
import com.flowfoundation.wallet.network.model.AccountSignRequest
import com.flowfoundation.wallet.network.model.LoginRequest
import com.flowfoundation.wallet.network.retrofit
import com.flowfoundation.wallet.page.main.MainActivity
import com.flowfoundation.wallet.page.restore.multirestore.model.RestoreGoogleDriveOption
import com.flowfoundation.wallet.page.restore.multirestore.model.RestoreOption
import com.flowfoundation.wallet.page.restore.multirestore.model.RestoreOptionModel
import com.flowfoundation.wallet.page.walletrestore.firebaseLogin
import com.flowfoundation.wallet.page.window.bubble.tools.pushBubbleStack
import com.flowfoundation.wallet.utils.ioScope
import com.flowfoundation.wallet.utils.loge
import com.flowfoundation.wallet.utils.setMultiBackupCreated
import com.flowfoundation.wallet.utils.setRegistered
import com.flowfoundation.wallet.utils.toast
import com.flowfoundation.wallet.utils.uiScope
import io.outblock.wallet.KeyManager
import io.outblock.wallet.KeyStoreCryptoProvider
import io.outblock.wallet.toFormatString
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import wallet.core.jni.HDWallet


class MultiRestoreViewModel : ViewModel(), OnTransactionStateChange {

    val optionChangeLiveData = MutableLiveData<RestoreOptionModel>()

    val googleDriveOptionChangeLiveData = MutableLiveData<RestoreGoogleDriveOption>()

    private val optionList = mutableListOf<RestoreOption>()
    private var currentOption = RestoreOption.RESTORE_START
    private var currentIndex = -1
    private var restoreUserName = ""
    private var restoreAddress = ""
    private val mnemonicList = mutableListOf<String>()
    private var currentTxId: String? = null
    private var mnemonicData = ""

    init {
        TransactionStateManager.addOnTransactionStateChange(this)
    }

    fun getRestoreOptionList(): List<RestoreOption> {
        return optionList
    }

    fun getCurrentIndex(): Int {
        return currentIndex
    }

    fun changeOption(option: RestoreOption, index: Int) {
        this.currentOption = option
        this.currentIndex = index
        optionChangeLiveData.postValue(RestoreOptionModel(option, index))
    }

    fun changeGoogleDriveOption(option: RestoreGoogleDriveOption) {
        googleDriveOptionChangeLiveData.postValue(option)
    }

    fun getMnemonicData(): String {
        return mnemonicData
    }

    fun toPinCode(data: String) {
        this.mnemonicData = data
        changeGoogleDriveOption(RestoreGoogleDriveOption.RESTORE_PIN)
    }

    fun isRestoreValid(): Boolean {
        return optionList.size >= 2
    }

    fun startRestore() {
        addCompleteOption()
        changeOption(optionList[0], 0)
    }

    private fun toNext() {
        ++currentIndex
        changeOption(optionList[currentIndex], currentIndex)
    }

    private fun addCompleteOption() {
        if (optionList.lastOrNull() != RestoreOption.RESTORE_COMPLETED) {
            optionList.remove(RestoreOption.RESTORE_COMPLETED)
            optionList.add(RestoreOption.RESTORE_COMPLETED)
        }
    }

    fun selectOption(option: RestoreOption, callback: (isSelected: Boolean) -> Unit) {
        if (optionList.contains(option)) {
            optionList.remove(option)
            callback.invoke(false)
        } else {
            optionList.add(option)
            callback.invoke(true)
        }
    }

    fun addMnemonicToTransaction(mnemonic: String) {
        mnemonicList.add(currentIndex, mnemonic)
        toNext()
    }

    fun restoreWallet() {
        if (WalletManager.wallet()?.walletAddress() == restoreAddress) {
            toast(msgRes = R.string.wallet_already_logged_in, duration = Toast.LENGTH_LONG)
            val activity = BaseActivity.getCurrentActivity() ?: return
            activity.finish()
            return
        }
        val account = AccountManager.list().firstOrNull { it.wallet?.walletAddress() == restoreAddress }
        if (account != null) {
            AccountManager.switch(account){}
            return
        }
        ioScope {
            try {
                val keyPair = KeyManager.generateKeyWithPrefix(generatePrefix(restoreUserName))
                val txId = CADENCE_ADD_PUBLIC_KEY.executeTransactionWithMultiKey {
                    arg { string(keyPair.public.toFormatString()) }
                    arg { uint8(SignatureAlgorithm.ECDSA_P256.index) }
                    arg { uint8(HashAlgorithm.SHA2_256.index) }
                    arg { ufix64Safe(1000) }
                }
                val transactionState = TransactionState(
                    transactionId = txId!!,
                    time = System.currentTimeMillis(),
                    state = FlowTransactionStatus.PENDING.num,
                    type = TransactionState.TYPE_ADD_PUBLIC_KEY,
                    data = ""
                )
                currentTxId = txId
                TransactionStateManager.newTransaction(transactionState)
                pushBubbleStack(transactionState)
            } catch (e: Exception) {
                loge(e)
                if (e is NoSuchElementException) {
                    toast(msgRes = R.string.restore_failed_option, duration = Toast.LENGTH_LONG)
                } else {
                    toast(msgRes = R.string.restore_failed, duration = Toast.LENGTH_LONG)
                }
                val activity = BaseActivity.getCurrentActivity() ?: return@ioScope
                activity.finish()
            }
        }
    }

    private fun syncAccountInfo() {
        ioScope {
            try {
                val cryptoProvider = KeyStoreCryptoProvider(KeyManager.getCurrentPrefix())
                val service = retrofit().create(ApiService::class.java)
                val providers = mnemonicList.map {
                    BackupCryptoProvider(HDWallet(it, ""))
                }
                val resp = service.signAccount(
                    AccountSignRequest(
                        AccountKey(publicKey = cryptoProvider.getPublicKey()),
                        providers.map {
                            val jwt = getFirebaseJwt()
                            AccountKeySignature(
                                publicKey = it.getPublicKey(),
                                signMessage = jwt,
                                signature = it.getUserSignature(jwt)
                            )
                        }.toList()
                    )
                )
                if (resp.status == 200) {
                    val activity = BaseActivity.getCurrentActivity() ?: return@ioScope
                    login(cryptoProvider) { isSuccess ->
                        uiScope {
                            if (isSuccess) {
                                delay(200)
                                MainActivity.relaunch(activity, clearTop = true)
                            } else {
                                toast(msg = "login failure")
                                activity.finish()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                loge(e)
            }
        }
    }

    private fun login(cryptoProvider: KeyStoreCryptoProvider, callback: (isSuccess: Boolean) -> Unit) {
        ioScope {
            getFirebaseUid { uid ->
                if (uid.isNullOrBlank()) {
                    callback.invoke(false)
                }
                runBlocking {
                    val catching = runCatching {
                        val deviceInfoRequest = DeviceInfoManager.getDeviceInfoRequest()
                        val service = retrofit().create(ApiService::class.java)
                        val resp = service.login(
                            LoginRequest(
                                signature = cryptoProvider.getUserSignature(
                                    getFirebaseJwt()
                                ),
                                accountKey = AccountKey(
                                    publicKey = cryptoProvider.getPublicKey(),
                                    hashAlgo = cryptoProvider.getHashAlgorithm().index,
                                    signAlgo = cryptoProvider.getSignatureAlgorithm().index
                                ),
                                deviceInfo = deviceInfoRequest
                            )
                        )
                        if (resp.data?.customToken.isNullOrBlank()) {
                            callback.invoke(false)
                        } else {
                            firebaseLogin(resp.data?.customToken!!) { isSuccess ->
                                if (isSuccess) {
                                    setRegistered()
                                    setMultiBackupCreated()
                                    ioScope {
                                        AccountManager.add(
                                            Account(
                                                userInfo = service.userInfo().data,
                                                prefix = KeyManager.getCurrentPrefix()
                                            )
                                        )
                                        clearUserCache()
                                        callback.invoke(true)
                                    }
                                } else {
                                    callback.invoke(false)
                                }
                            }
                        }
                    }

                    if (catching.isFailure) {
                        loge(catching.exceptionOrNull())
                        callback.invoke(false)
                    }
                }
            }
        }
    }

    private suspend fun getFirebaseUid(callback: (uid: String?) -> Unit) {
        val uid = Firebase.auth.currentUser?.uid
        if (!uid.isNullOrBlank()) {
            callback.invoke(uid)
            return
        }

        getFirebaseJwt(true)

        callback.invoke(Firebase.auth.currentUser?.uid)
    }

    fun addWalletInfo(userName: String, address: String) {
        restoreUserName = userName
        restoreAddress = address
    }

    private suspend fun String.executeTransactionWithMultiKey(arguments: CadenceArgumentsBuilder.() -> Unit): String? {
        val args = CadenceArgumentsBuilder().apply { arguments(this) }
        val providers = mnemonicList.map {
            BackupCryptoProvider(HDWallet(it, ""))
        }
        return try {
            sendTransactionWithMultiSignature(providers = providers, builder = {
                args.build().forEach { arg(it) }
                walletAddress(restoreAddress)
                script(this@executeTransactionWithMultiKey)
            })
        } catch (e: Exception) {
            loge(e)
            null
        }
    }

    override fun onTransactionStateChange() {
        val transactionList = TransactionStateManager.getTransactionStateList()
        val transaction =
            transactionList.lastOrNull { it.type == TransactionState.TYPE_ADD_PUBLIC_KEY }
        transaction?.let { state ->
            if (currentTxId == state.transactionId && state.isSuccess()) {
                currentTxId = null
                syncAccountInfo()
            }
        }
    }
}