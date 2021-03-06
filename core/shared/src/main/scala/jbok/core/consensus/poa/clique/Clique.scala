package jbok.core.consensus.poa.clique

import cats.data.OptionT
import cats.effect.ConcurrentEffect
import cats.implicits._
import jbok.codec.rlp.RlpCodec
import jbok.codec.rlp.implicits._
import jbok.core.consensus.poa.clique.Clique._
import jbok.core.ledger.History
import jbok.core.models._
import jbok.crypto._
import jbok.crypto.signature._
import scalacache._
import scodec.bits._
import jbok.core.config.Configs.MiningConfig
import jbok.core.config.GenesisConfig
import jbok.persistent.CacheBuilder

import scala.concurrent.duration._

class Clique[F[_]](
    val config: MiningConfig,
    val history: History[F],
    val proposals: Map[Address, Boolean], // Current list of proposals we are pushing
    val keyPair: Option[KeyPair]
)(implicit F: ConcurrentEffect[F], C: Cache[Snapshot]) {
  private[this] val log = jbok.common.log.getLogger("Clique")

  import config._

  lazy val signer: Address = Address(keyPair.get)

  def sign(bv: ByteVector): F[CryptoSignature] =
    Signature[ECDSA].sign[F](bv.toArray, keyPair.get, history.chainId)

  def applyHeaders(
      number: BigInt,
      hash: ByteVector,
      parents: List[BlockHeader],
      headers: List[BlockHeader] = Nil
  ): F[Snapshot] = {
    val snap =
      OptionT(Snapshot.loadSnapshot[F](history.db, hash))
        .orElseF(if (number == 0) genesisSnapshot.map(_.some) else F.pure(None))

    snap.value flatMap {
      case Some(s) =>
        // Previous snapshot found, apply any pending headers on top of it
        log.trace(s"applying ${headers.length} headers")
        for {
          newSnap <- Snapshot.applyHeaders[F](s, headers)
          _       <- Snapshot.storeSnapshot[F](newSnap, history.db, checkpointInterval)
        } yield newSnap

      case None =>
        // No snapshot for this header, gather the header and move backward(recur)
        for {
          (h, p) <- if (parents.nonEmpty) {
            // If we have explicit parents, pick from there (enforced)
            F.pure((parents.last, parents.slice(0, parents.length - 1)))
          } else {
            // No explicit parents (or no more left), reach out to the database
            history.getBlockHeaderByHash(hash).map(header => header.get -> parents)
          }
          snap <- applyHeaders(number - 1, h.parentHash, p, h :: headers)
        } yield snap
    }
  }

  private def genesisSnapshot: F[Snapshot] = {
    log.trace(s"making a genesis snapshot")
    for {
      genesis <- history.genesisHeader
      n = (genesis.extraData.length - extraVanity - extraSeal).toInt / 20
      signers: Set[Address] = (0 until n)
        .map(i => Address(genesis.extraData.slice(i * 20 + extraVanity, i * 20 + extraVanity + 20)))
        .toSet
      snap = Snapshot(config, 0, genesis.hash, signers)
      _ <- Snapshot.storeSnapshot[F](snap, history.db, checkpointInterval)
      _ = log.trace(s"stored genesis with ${signers.size} signers")
    } yield snap
  }
}

object Clique {
  val extraVanity = 32 // Fixed number of extra-data prefix bytes reserved for signer vanity
  val extraSeal   = 65 // Fixed number of extra-data suffix bytes reserved for signer seal
  val ommersHash = RlpCodec
    .encode(List.empty[BlockHeader])
    .require
    .bytes
    .kec256 // Always Keccak256(RLP([])) as uncles are meaningless outside of PoW.
  val diffInTurn    = BigInt(11)              // Block difficulty for in-turn signatures
  val diffNoTurn    = BigInt(10)              // Block difficulty for out-of-turn signatures
  val nonceAuthVote = hex"0xffffffffffffffff" // Magic nonce number to vote on adding a new signer
  val nonceDropVote = hex"0x0000000000000000" // Magic nonce number to vote on removing a signer.

  val inMemorySnapshots: Int     = 128
  val inMemorySignatures: Int    = 1024
  val wiggleTime: FiniteDuration = 500.millis

  def apply[F[_]](
      config: MiningConfig,
      genesisConfig: GenesisConfig,
      history: History[F],
      keyPair: Option[KeyPair]
  )(implicit F: ConcurrentEffect[F]): F[Clique[F]] =
    for {
      genesisBlock <- history.getBlockByNumber(0)
      _ <- if (genesisBlock.isEmpty) {
        history.initGenesis(genesisConfig)
      } else {
        F.unit
      }
      cache <- CacheBuilder.build[F, Snapshot](inMemorySnapshots)
    } yield new Clique[F](config, history, Map.empty, keyPair)(F, cache)

  private[clique] def fillExtraData(signers: List[Address]): ByteVector =
    ByteVector.fill(extraVanity)(0.toByte) ++ signers.foldLeft(ByteVector.empty)(_ ++ _.bytes) ++ ByteVector.fill(
      extraSeal)(0.toByte)

  def sigHash(header: BlockHeader): ByteVector = {
    val bytes = RlpCodec.encode(header.copy(extraData = header.extraData.dropRight(extraSeal))).require.bytes
    bytes.kec256
  }

  /** Retrieve the signature from the header extra-data */
  def ecrecover(header: BlockHeader): Option[Address] = {
    val signature               = header.extraData.takeRight(extraSeal)
    val hash                    = sigHash(header)
    val sig                     = CryptoSignature(signature.toArray)
    val chainId: Option[BigInt] = ECDSAChainIdConvert.getChainId(sig.v)
    chainId.flatMap(
      Signature[ECDSA]
        .recoverPublic(hash.toArray, sig, _)
        .map(pub => Address(pub.bytes.kec256)))
  }
}
