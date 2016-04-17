package fr.acinq.bitcoin

import java.io.{ByteArrayOutputStream, InputStream, OutputStream}

import fr.acinq.bitcoin.Script.Runner
import Protocol._

import scala.collection.mutable.ArrayBuffer

object OutPoint extends BtcMessage[OutPoint] {
  def apply(tx: Transaction, index: Int) = new OutPoint(tx.hash, index)

  override def read(input: InputStream, protocolVersion: Long): OutPoint = OutPoint(hash(input), uint32(input))

  override def write(input: OutPoint, out: OutputStream, protocolVersion: Long) = {
    out.write(input.hash)
    writeUInt32(input.index, out)
  }

  def isCoinbase(input: OutPoint) = input.index == 0xffffffffL && input.hash == Hash.Zeroes

  def isNull(input: OutPoint) = isCoinbase(input)

}

/**
  * an out point is a reference to a specific output in a specific transaction that we want to claim
  *
  * @param hash reversed sha256(sha256(tx)) where tx is the transaction we want to refer to
  * @param index index of the output in tx that we want to refer to
  */
case class OutPoint(hash: BinaryData, index: Long) {
  require(hash.length == 32)
  require(index >= -1)

  /**
    *
    * @return the id of the transaction this output belongs to
    */
  def txid = hash.data.reverse
}

object TxIn extends BtcMessage[TxIn] {
  def apply(outPoint: OutPoint, signatureScript: Seq[ScriptElt], sequence: Long): TxIn = new TxIn(outPoint, Script.write(signatureScript), sequence)

  /* Setting nSequence to this value for every input in a transaction disables nLockTime. */
  val SEQUENCE_FINAL = 0xffffffffL

  /* Below flags apply in the context of BIP 68*/
  /* If this flag set, CTxIn::nSequence is NOT interpreted as a relative lock-time. */
  val SEQUENCE_LOCKTIME_DISABLE_FLAG = (1L << 31)

  /* If CTxIn::nSequence encodes a relative lock-time and this flag
   * is set, the relative lock-time has units of 512 seconds,
   * otherwise it specifies blocks with a granularity of 1. */
  val SEQUENCE_LOCKTIME_TYPE_FLAG = (1L << 22)

  /* If CTxIn::nSequence encodes a relative lock-time, this mask is
   * applied to extract that lock-time from the sequence field. */
  val SEQUENCE_LOCKTIME_MASK = 0x0000ffffL

  /* In order to use the same number of bits to encode roughly the
   * same wall-clock duration, and because blocks are naturally
   * limited to occur every 600s on average, the minimum granularity
   * for time-based relative lock-time is fixed at 512 seconds.
   * Converting from CTxIn::nSequence to seconds is performed by
   * multiplying by 512 = 2^9, or equivalently shifting up by
   * 9 bits. */
  val SEQUENCE_LOCKTIME_GRANULARITY = 9

  override def read(input: InputStream, protocolVersion: Long): TxIn = TxIn(outPoint = OutPoint.read(input), signatureScript = script(input), sequence = uint32(input))

  override def write(input: TxIn, out: OutputStream, protocolVersion: Long) = {
    OutPoint.write(input.outPoint, out)
    writeScript(input.signatureScript, out)
    writeUInt32(input.sequence, out)
  }

  override def validate(input: TxIn): Unit = {
    require(input.signatureScript.length <= MaxScriptElementSize, s"signature script is ${input.signatureScript.length} bytes, limit is $MaxScriptElementSize bytes")
  }

  def coinbase(script: BinaryData): TxIn = {
    require(script.length >= 2 && script.length <= 100, "coinbase script length must be between 2 and 100")
    TxIn(OutPoint(new Array[Byte](32), 0xffffffffL), script, sequence = 0xffffffffL)
  }

  def coinbase(script: Seq[ScriptElt]): TxIn = coinbase(Script.write(script))
}

/**
  * Transaction input
  *
  * @param outPoint Previous output transaction reference
  * @param signatureScript Computational Script for confirming transaction authorization
  * @param sequence Transaction version as defined by the sender. Intended for "replacement" of transactions when
  *                 information is updated before inclusion into a block. Unused for now.
  */
case class TxIn(outPoint: OutPoint, signatureScript: BinaryData, sequence: Long) {
  def isFinal: Boolean = sequence == TxIn.SEQUENCE_FINAL
}

object TxOut extends BtcMessage[TxOut] {
  def apply(amount: Satoshi, publicKeyScript: Seq[ScriptElt]): TxOut = new TxOut(amount, Script.write(publicKeyScript))

  override def read(input: InputStream, protocolVersion: Long): TxOut = TxOut(Satoshi(uint64(input)), script(input))

  override def write(input: TxOut, out: OutputStream, protocolVersion: Long) = {
    writeUInt64(input.amount.amount, out)
    writeScript(input.publicKeyScript, out)
  }

  override def validate(input: TxOut): Unit = {
    import input._
    require(amount.amount >= 0, s"invalid txout amount: $amount")
    require(amount.amount <= MaxMoney, s"invalid txout amount: $amount")
    require(publicKeyScript.length < MaxScriptElementSize, s"public key script is ${publicKeyScript.length} bytes, limit is $MaxScriptElementSize bytes")
  }
}

/**
  * Transaction output
  *
  * @param amount amount in Satoshis
  * @param publicKeyScript Usually contains the public key as a Bitcoin script setting up conditions to claim this output.
  */
case class TxOut(amount: Satoshi, publicKeyScript: BinaryData)

object ScriptWitness extends BtcMessage[ScriptWitness] {
  val empty = ScriptWitness(Seq.empty[BinaryData])

  override def write(t: ScriptWitness, out: OutputStream, protocolVersion: Long): Unit =
    writeCollection[BinaryData](t.stack, (b:BinaryData, o:OutputStream, _: Long) => writeScript(b, o), out, protocolVersion)

  override def read(in: InputStream, protocolVersion: Long): ScriptWitness =
    ScriptWitness(readCollection[BinaryData](in, (i: InputStream, _:Long) => script(i), None, protocolVersion))
}

/**
  * a script witness is just a stack of data
  * there is one script witness per transaction input
  *
  * @param stack items to be pushed on the stack
  */
case class ScriptWitness(stack: Seq[BinaryData]) {
  def isNull = stack.isEmpty
  def isNotNull = !isNull
}

object Transaction extends BtcMessage[Transaction] {
  val SERIALIZE_TRANSACTION_WITNESS = 0x40000000L

  def serializeTxWitness(version: Long): Boolean = (version & SERIALIZE_TRANSACTION_WITNESS) != 0

  def isNotNull(witness: Seq[ScriptWitness]) = witness.exists(_.isNotNull)

  def isNull(witness: Seq[ScriptWitness]) = !isNotNull(witness)

  def apply(version: Long, txIn: Seq[TxIn], txOut: Seq[TxOut], lockTime: Long) = new Transaction(version, txIn, txOut, lockTime, Seq.fill(txIn.size)(ScriptWitness.empty))

  override def read(input: InputStream, protocolVersion: Long): Transaction = {
    val tx = Transaction(uint32(input), readCollection[TxIn](input, protocolVersion), Seq.empty[TxOut], 0)
    val (flags, tx1) = if (tx.txIn.isEmpty && serializeTxWitness(protocolVersion)) {
      // we just read the 0x00 marker
      val flags = uint8(input)
      val txIn = readCollection[TxIn](input, protocolVersion)
      if (flags == 0 && !txIn.isEmpty) throw new RuntimeException("Extended transaction format unnecessarily used")
      val txOut = readCollection[TxOut](input, protocolVersion)
      (flags, tx.copy(txIn = txIn, txOut = txOut))
    } else (0, tx.copy(txOut = readCollection[TxOut](input, protocolVersion)))

    val tx2 = flags match {
      case 0 => tx1.copy(lockTime = uint32(input))
      case 1 =>
        val witness = new ArrayBuffer[ScriptWitness]()
        for (i <- 0 until tx1.txIn.size) witness += ScriptWitness.read(input, protocolVersion)
        tx1.copy(witness = witness.toSeq, lockTime = uint32(input))
      case _ => throw new RuntimeException(s"Unknown transaction optional data $flags")
    }

    tx2
  }

  override def write(tx: Transaction, out: OutputStream, protocolVersion: Long) = {
    if (serializeTxWitness(protocolVersion) && isNotNull(tx.witness)) {
      writeUInt32(tx.version, out)
      writeUInt8(0x00, out)
      writeUInt8(0x01, out)
      writeCollection(tx.txIn, out, protocolVersion)
      writeCollection(tx.txOut, out, protocolVersion)
      for (i <- 0 until tx.txIn.size) ScriptWitness.write(tx.witness(i), out, protocolVersion)
      writeUInt32(tx.lockTime, out)
    } else {
      writeUInt32(tx.version, out)
      writeCollection(tx.txIn, out, protocolVersion)
      writeCollection(tx.txOut, out, protocolVersion)
      writeUInt32(tx.lockTime, out)
    }
  }

  override def validate(input: Transaction): Unit = {
    require(input.txIn.nonEmpty, "input list cannot be empty")
    require(input.txOut.nonEmpty, "output list cannot be empty")
    require(Transaction.write(input).size <= MaxBlockSize)
    require(input.txOut.map(_.amount.amount).sum <= MaxMoney, "sum of outputs amount is invalid")
    input.txIn.map(TxIn.validate)
    input.txOut.map(TxOut.validate)
    val outPoints = input.txIn.map(_.outPoint)
    require(outPoints.size == outPoints.toSet.size, "duplicate inputs")
    if (Transaction.isCoinbase(input)) {
      require(input.txIn(0).signatureScript.size >= 2, "coinbase script size")
      require(input.txIn(0).signatureScript.size <= 100, "coinbase script size")
    } else {
      require(input.txIn.forall(in => !OutPoint.isCoinbase(in.outPoint)), "prevout is null")
    }
  }

  def isCoinbase(input: Transaction) = input.txIn.size == 1 && OutPoint.isCoinbase(input.txIn(0).outPoint)

  /**
    * prepare a transaction for signing a specific input
    *
    * @param tx input transaction
    * @param inputIndex index of the tx input that is being processed
    * @param previousOutputScript public key script of the output claimed by this tx input
    * @param sighashType signature hash type
    * @return a new transaction with proper inputs and outputs according to SIGHASH_TYPE rules
    */
  def prepareForSigning(tx: Transaction, inputIndex: Int, previousOutputScript: Array[Byte], sighashType: Int): Transaction = {
    val filteredScript = Script.write(Script.parse(previousOutputScript).filterNot(_ == OP_CODESEPARATOR))

    def removeSignatureScript(txin: TxIn): TxIn = txin.copy(signatureScript = Array.empty[Byte])
    def removeAllSignatureScripts(tx: Transaction): Transaction = tx.copy(txIn = tx.txIn.map(removeSignatureScript))
    def updateSignatureScript(tx: Transaction, index: Int, script: Array[Byte]): Transaction = tx.copy(txIn = tx.txIn.updated(index, tx.txIn(index).copy(signatureScript = script)))
    def resetSequence(txins: Seq[TxIn], inputIndex: Int): Seq[TxIn] = for (i <- 0 until txins.size) yield {
      if (i == inputIndex) txins(i)
      else txins(i).copy(sequence = 0)
    }

    val txCopy = {
      // remove all signature scripts, and replace the sig script for the input that we are processing with the
      // pubkey script of the output that we are trying to claim
      val tx1 = removeAllSignatureScripts(tx)
      val tx2 = updateSignatureScript(tx1, inputIndex, filteredScript)

      val tx3 = if (isHashNone(sighashType)) {
        // hash none: remove all outputs
        val inputs = resetSequence(tx2.txIn, inputIndex)
        tx2.copy(txIn = inputs, txOut = List())
      }
      else if (isHashSingle(sighashType)) {
        // hash single: remove all outputs but the one that we are trying to claim
        val inputs = resetSequence(tx2.txIn, inputIndex)
        val outputs = for (i <- 0 to inputIndex) yield {
          if (i == inputIndex) tx2.txOut(inputIndex)
          else TxOut(Satoshi(-1), Array.empty[Byte])
        }
        tx2.copy(txIn = inputs, txOut = outputs)
      }
      else tx2
      // anyone can pay: remove all inputs but the one that we are processing
      val tx4 = if (isAnyoneCanPay(sighashType)) tx3.copy(txIn = List(tx3.txIn(inputIndex))) else tx3
      tx4
    }
    txCopy
  }

  /**
    * hash a tx for signing (pre-segwit)
    *
    * @param tx input transaction
    * @param inputIndex index of the tx input that is being processed
    * @param previousOutputScript public key script of the output claimed by this tx input
    * @param sighashType signature hash type
    * @return a hash which can be used to sign the referenced tx input
    */
  def hashForSigning(tx: Transaction, inputIndex: Int, previousOutputScript: BinaryData, sighashType: Int): Seq[Byte] = {
    if (isHashSingle(sighashType) && inputIndex >= tx.txOut.length) {
      Hash.One
    } else {
      val txCopy = prepareForSigning(tx, inputIndex, previousOutputScript, sighashType)
      Crypto.hash256(Transaction.write(txCopy) ++ writeUInt32(sighashType))
    }
  }

  /**
    * hash a tx for signing
    * @param tx input transaction
    * @param inputIndex index of the tx input that is being processed
    * @param previousOutputScript public key script of the output claimed by this tx input
    * @param sighashType signature hash type
    * @param sighashType
    * @param amount
    * @return
    */
  def hashForSigning(tx: Transaction, inputIndex: Int, previousOutputScript: BinaryData, sighashType: Int, amount: Long, signatureVersion: Int): Seq[Byte] = {
    signatureVersion match {
      case 1 =>
        val hashPrevOut: BinaryData = if (!isAnyoneCanPay(sighashType)) {
          Crypto.hash256(tx.txIn.map(_.outPoint).map(OutPoint.write(_, Protocol.PROTOCOL_VERSION)).flatten)
        } else Hash.Zeroes

        val hashSequence: BinaryData = if (!isAnyoneCanPay(sighashType) && !isHashSingle(sighashType) && !isHashNone(sighashType)) {
          Crypto.hash256(tx.txIn.map(_.sequence).map(Protocol.writeUInt32).flatten)
        } else Hash.Zeroes

        val hashOutputs: BinaryData =  if (!isHashSingle(sighashType) && !isHashNone(sighashType)) {
          Crypto.hash256(tx.txOut.map(TxOut.write(_, Protocol.PROTOCOL_VERSION)).flatten)
        } else if (isHashSingle(sighashType) && inputIndex < tx.txOut.size) {
          Crypto.hash256(TxOut.write(tx.txOut(inputIndex), Protocol.PROTOCOL_VERSION))
        } else Hash.Zeroes

        val out = new ByteArrayOutputStream()
        Protocol.writeUInt32(tx.version, out)
        out.write(hashPrevOut)
        out.write(hashSequence)
        out.write(OutPoint.write(tx.txIn(inputIndex).outPoint, Protocol.PROTOCOL_VERSION))
        out.write(previousOutputScript)
        Protocol.writeUInt64(amount, out)
        Protocol.writeUInt32(tx.txIn(inputIndex).sequence, out)
        out.write(hashOutputs)
        Protocol.writeUInt32(tx.lockTime, out)
        Protocol.writeUInt32(sighashType, out)
        val preimage: BinaryData = out.toByteArray
        Crypto.hash256(preimage)
      case _ =>
        hashForSigning(tx, inputIndex, previousOutputScript, sighashType)
    }
  }

  /**
    *
    * @param tx input transaction
    * @param inputIndex index of the tx input that is being processed
    * @param previousOutputScript public key script of the output claimed by this tx input
    * @param sighashType signature hash type, which will be appended to the signature
    * @param privateKey private key
    * @param randomize if false, the output signature will not be randomized (use for testing only)
    * @return the encoded signature of this tx for this specific tx input
    */
  def signInput(tx: Transaction, inputIndex: Int, previousOutputScript: Seq[Byte], sighashType: Int, privateKey: Seq[Byte], randomize: Boolean = true): Seq[Byte] = {
    val hash = hashForSigning(tx, inputIndex, previousOutputScript, sighashType)
    val (r, s) = Crypto.sign(hash, privateKey.take(32), randomize)
    val sig = Crypto.encodeSignature(r, s)
    sig :+ (sighashType.toByte)
  }

  /**
    * Sign a transaction. Cannot partially sign. All the input are signed with SIGHASH_ALL
    *
    * @param input transaction to sign
    * @param signData list of data for signing: previous tx output script and associated private key
    * @param randomize if false, signature will not be randomized. Use for debugging purposes only!
    * @return a new signed transaction
    */
  def sign(input: Transaction, signData: Seq[SignData], randomize: Boolean = true): Transaction = {

    require(signData.length == input.txIn.length, "There should be signing data for every transaction")

    // sign each input
    val signedInputs = for (i <- 0 until input.txIn.length) yield {
      val sig = signInput(input, i, signData(i).prevPubKeyScript, SIGHASH_ALL, signData(i).privateKey, randomize)

      // this is the public key that is associated with the private key we used for signing
      val publicKey = Crypto.publicKeyFromPrivateKey(signData(i).privateKey)

      // signature script: push signature and public key
      val sigScript = Script.write(OP_PUSHDATA(sig) :: OP_PUSHDATA(publicKey) :: Nil)
      input.txIn(i).copy(signatureScript = sigScript)
    }

    input.copy(txIn = signedInputs)
  }

  /**
    * checks that a transaction correctly spends its inputs (i.e is properly signed)
    *
    * @param tx transaction to be checked
    * @param inputs previous tx that are being spent
    * @param scriptFlags script execution flags
    * @throws RuntimeException if the transaction is not valid (i.e executing input and output scripts does not yield "true")
    */
  def correctlySpends(tx: Transaction, inputs: Seq[Transaction], scriptFlags: Int, callback: Option[Runner.Callback]): Unit = {
    val txMap = inputs.map(t => t.txid -> t).toMap
    for (i <- 0 until tx.txIn.length if !OutPoint.isCoinbase(tx.txIn(i).outPoint)) {
      val prevTx = txMap(tx.txIn(i).outPoint.txid)
      val prevOutputScript = prevTx.txOut(tx.txIn(i).outPoint.index.toInt).publicKeyScript
      val amount = prevTx.txOut(tx.txIn(i).outPoint.index.toInt).amount
      val ctx = new Script.Context(tx, i, amount.amount)
      val runner = new Script.Runner(ctx, scriptFlags, callback)
      if (!runner.verifyScripts(tx.txIn(i).signatureScript, prevOutputScript, tx.witness(i))) throw new RuntimeException(s"tx ${tx.txid} does not spend its input # $i")
    }
  }

  def correctlySpends(tx: Transaction, inputs: Seq[Transaction], scriptFlags: Int): Unit = correctlySpends(tx, inputs, scriptFlags, None)
}

object SignData {
  def apply(prevPubKeyScript: Seq[ScriptElt], privateKey: BinaryData): SignData = new SignData(Script.write(prevPubKeyScript), privateKey)
}

/**
  * data for signing pay2pk transaction
  *
  * @param prevPubKeyScript previous output public key script
  * @param privateKey private key associated with the previous output public key
  */
case class SignData(prevPubKeyScript: BinaryData, privateKey: BinaryData)

/**
  * Transaction
  *
  * @param version Transaction data format version
  * @param txIn Transaction inputs
  * @param txOut Transaction outputs
  * @param lockTime The block number or timestamp at which this transaction is locked
  */
case class Transaction(version: Long, txIn: Seq[TxIn], txOut: Seq[TxOut], lockTime: Long, witness: Seq[ScriptWitness]) {
  lazy val hash = Crypto.hash256(Transaction.write(this))
  lazy val txid = hash.reverse

  /**
    *
    * @param blockHeight current block height
    * @param blockTime current block time
    * @return true if the transaction is final
    */
  def isFinal(blockHeight: Long, blockTime: Long): Boolean = lockTime match {
    case 0 => true
    case value if value < LockTimeThreshold && value < blockHeight => true
    case value if value >= LockTimeThreshold && value < blockTime => true
    case _ if txIn.exists(!_.isFinal) => false
    case _ => true
  }

  /**
    *
    * @param i index of the tx input to update
    * @param sigScript new signature script
    * @return a new transaction that is of copy of this one but where the signature script of the ith input has been replace by sigscript
    */
  def updateSigScript(i: Int, sigScript: BinaryData) : Transaction = this.copy(txIn = txIn.updated(i, txIn(i).copy(signatureScript = sigScript)))

  /**
    *
    * @param i index of the tx input to update
    * @param sigScript new signature script
    * @return a new transaction that is of copy of this one but where the signature script of the ith input has been replace by sigscript
    */
  def updateSigScript(i: Int, sigScript: Seq[ScriptElt]) : Transaction = updateSigScript(i, Script.write(sigScript))

  /**
    *
    * @param input input to add the tx
    * @return a new transaction which includes the newly added input
    */
  def addInput(input: TxIn) : Transaction = this.copy(txIn = this.txIn :+ input)

  /**
    *
    * @param output output to add to the tx
    * @return a new transaction which includes the newly added output
    */
  def addOutput(output: TxOut) : Transaction = this.copy(txOut = this.txOut :+ output)
}