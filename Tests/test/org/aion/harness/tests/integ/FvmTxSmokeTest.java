package org.aion.harness.tests.integ;

import static org.aion.harness.tests.contracts.Assertions.assertRpcSuccess;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.aion.harness.kernel.Address;
import org.aion.harness.kernel.PrivateKey;
import org.aion.harness.kernel.RawTransaction;
import org.aion.harness.kernel.Transaction;
import org.aion.harness.main.LocalNode;
import org.aion.harness.main.Network;
import org.aion.harness.main.NodeConfigurations;
import org.aion.harness.main.NodeConfigurations.DatabaseOption;
import org.aion.harness.main.NodeFactory;
import org.aion.harness.main.NodeFactory.NodeType;
import org.aion.harness.main.NodeListener;
import org.aion.harness.main.ProhibitConcurrentHarness;
import org.aion.harness.main.RPC;
import org.aion.harness.main.event.IEvent;
import org.aion.harness.main.event.PrepackagedLogEvents;
import org.aion.harness.main.types.ReceiptHash;
import org.aion.harness.main.types.TransactionReceipt;
import org.aion.harness.result.FutureResult;
import org.aion.harness.result.LogEventResult;
import org.aion.harness.result.Result;
import org.aion.harness.result.RpcResult;
import org.aion.harness.result.TransactionResult;
import org.aion.harness.util.SimpleLog;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.io.FileUtils;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

public class FvmTxSmokeTest {
    private static final String BUILT_KERNEL = System.getProperty("user.dir") + "/aion";
    private static final String PREMINED_KEY = "4c3c8a7c0292bc55d97c50b4bdabfd47547757d9e5c194e89f66f25855baacd0";
    private static final long ENERGY_LIMIT = 1_234_567L;
    private static final long ENERGY_PRICE = 10_010_020_345L;

    private static final SimpleLog log = new SimpleLog("org.aion.harness.tests.integ.FvmTxSmokeTest");

    private static LocalNode node;
    private static RPC rpc;
    private static NodeListener listener;
    private static PrivateKey preminedPrivateKey;

    @Rule
    public Timeout globalTimeout = Timeout.seconds(200);

    @BeforeClass
    public static void setup() throws Exception {
        ProhibitConcurrentHarness.acquireTestLock();
        preminedPrivateKey = PrivateKey.fromBytes(Hex.decodeHex(PREMINED_KEY));

        NodeConfigurations configurations = NodeConfigurations.alwaysUseBuiltKernel(Network.CUSTOM, BUILT_KERNEL, DatabaseOption.PRESERVE_DATABASE);

        node = NodeFactory.getNewLocalNodeInstance(NodeType.JAVA_NODE);
        node.configure(configurations);
        Result result = node.initialize();
        log.log(result);
        assertTrue(result.isSuccess());
        Result startResult = node.start();
        assertTrue("Kernel startup error: " + startResult.getError(),
            startResult.isSuccess());
        assertTrue(node.isAlive());
        rpc = new RPC("127.0.0.1", "8545");
        listener = NodeListener.listenTo(node);
    }

    @AfterClass
    public static void tearDown() throws Exception {
        System.out.println("Node stop: " + node.stop());
        node = null;
        rpc = null;
        listener = null;
        destroyLogs();

        // If we close and reopen the DB too quickly we get an error... this sleep tries to avoid
        // this issue so that the DB lock is released in time.
        Thread.sleep(TimeUnit.SECONDS.toMillis(30));
        ProhibitConcurrentHarness.releaseTestLock();
    }

    /**
     * <code>data</code> field for deploying the contract.  This is the binary output of
     * solc for the solidity program located in test_resources/Bytes16Holder.sol
     */
    private final static byte[] DEPLOY_DATA;

    /**
     * <code>data</code> field for solidity method call on the above contract:
     *   set(0x00000000000000000000000021000161)
     */
    private final static byte[] SET_DATA_1;

    /**
     * <code>data</code> field for solidity method call on the above contract:
     *   set(0x00000000000000000021000441534446)
     */
    private final static byte[] SET_DATA_2;

    /**
     * <code>data</code> field for solidity method call on the above contract:
     *   get()
     */
    private final static byte[] GET_DATA;

    static {
        try {
            DEPLOY_DATA = Hex.decodeHex("605060405234156100105760006000fd5b5b6101026000600050819090600019169055505b610029565b60e0806100376000396000f30060506040526000356c01000000000000000000000000900463ffffffff1680636d4ce63c14603b578063ac6effc214606a576035565b60006000fd5b341560465760006000fd5b604c608f565b60405180826000191660001916815260100191505060405180910390f35b341560755760006000fd5b608d60048080356000191690601001909190505060a0565b005b60006000600050549050609d565b90565b806000600050819090600019169055505b505600a165627a7a72305820d6585e5cd5b612562558cd595d992e3cde87a53ed8765ab62c9662cca20d914a0029");
            SET_DATA_1 = Hex.decodeHex("ac6effc200000000000000000000000021000161");
            SET_DATA_2 = Hex.decodeHex("ac6effc200000000000000000021000441534446");
            GET_DATA = Hex.decodeHex("6d4ce63c");
        } catch (DecoderException dx) {
            throw new IllegalStateException(
                "Test error: could not initialize the data to be used in the test.", dx);
        }
    }

    @Test
    public void test() throws Exception {
        // build contract deployment Tx
        TransactionResult deploy = RawTransaction.buildAndSignGeneralTransaction(
            this.preminedPrivateKey,
            getNonce(),
            null,
            DEPLOY_DATA,
            ENERGY_LIMIT,
            ENERGY_PRICE,
            BigInteger.ZERO /* amount */);
        if(! deploy.isSuccess()) {
            throw new IllegalStateException("failed to construct the deployment tx");
        }

        // send contract deployment Tx
        TransactionReceipt deployReceipt = sendTransaction(deploy.getTransaction());
        assertThat("expected address of deployed contract to be present in receipt of deployment tx",
            deployReceipt.getAddressOfDeployedContract().isPresent(), is(true));
        Address contract = deployReceipt.getAddressOfDeployedContract().get();

        // check initial state of deployed contract
        byte[] result = this.rpc.call(new Transaction(contract, GET_DATA));
        assertThat(
            "initial state of deployed contract",
            Arrays.equals(result, new byte[] {
                0x0, 0x0, 0x0, 0x0, 0x0, 0x0, 0x0, 0x0,
                0x0, 0x0, 0x0, 0x0, 0x0, 0x0, 0x1, 0x2
            }),
            is(true));

        // set "data" field of the contract
        TransactionResult tx1 =
            RawTransaction.buildAndSignGeneralTransaction(this.preminedPrivateKey, getNonce(), contract,
                SET_DATA_1, ENERGY_LIMIT, ENERGY_PRICE, BigInteger.ZERO /* amount */);
        if(! tx1.isSuccess()) {
            throw new IllegalStateException("failed to construct transaction 1 for setting data");
        }
        TransactionReceipt tx1Receipt = sendTransaction(tx1.getTransaction());
        assertThat(tx1Receipt, is(not(nullValue()))); // can get more rigourous with this

        // check state of deployed contract after tx1
        byte[] resultAfterTx1 = this.rpc.call(new Transaction(contract, GET_DATA));
        assertThat(
            "initial state of deployed contract",
            Arrays.equals(resultAfterTx1, new byte[] {
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00, 0x21, 0x00, 0x01, 0x61
            }),
            is(true));

        // set "data" field of the contract
        TransactionResult tx2 =
            RawTransaction.buildAndSignGeneralTransaction(this.preminedPrivateKey, getNonce(), contract,
                SET_DATA_2, ENERGY_LIMIT, ENERGY_PRICE, BigInteger.ZERO /* amount */);
        if(! tx2.isSuccess()) {
            throw new IllegalStateException("failed to construct transaction 1 for setting data");
        }
        TransactionReceipt tx2Receipt = sendTransaction(tx2.getTransaction());
        assertThat(tx2Receipt, is(not(nullValue()))); // can get more rigourous with this

        // check state of deployed contract after tx1
        byte[] resultAfterTx2 = this.rpc.call(new Transaction(contract, GET_DATA));
        assertThat(
            "initial state of deployed contract",
            Arrays.equals(resultAfterTx2, new byte[] {
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                0x00, 0x21, 0x00, 0x04, 0x41, 0x53, 0x44, 0x46
            }),
            is(true));
    }

    private BigInteger getNonce() throws InterruptedException {
        RpcResult<BigInteger> nonceResult = this.rpc.getNonce(this.preminedPrivateKey.getAddress());
        assertRpcSuccess(nonceResult);
        return nonceResult.getResult();
    }

    private TransactionReceipt sendTransaction(RawTransaction transaction) throws InterruptedException {
        // we want to ensure that the transaction gets sealed into a block.
        IEvent transactionIsSealed = PrepackagedLogEvents.getTransactionSealedEvent(transaction);
        FutureResult<LogEventResult> future = this.listener.listenForEvent(transactionIsSealed, 5, TimeUnit.MINUTES);

        // Send the transaction off.
        log.log("Sending the transaction...");
        RpcResult<ReceiptHash> sendResult = this.rpc.sendTransaction(transaction);
        assertRpcSuccess(sendResult);

        // Wait on the future to complete and ensure we saw the transaction get sealed.
        log.log("Waiting for the transaction to process...");
        LogEventResult listenResult = future.get();
        assertTrue(listenResult.eventWasObserved());
        log.log("Transaction was sealed into a block.");

        ReceiptHash hash = sendResult.getResult();

        RpcResult<TransactionReceipt> receiptResult = this.rpc.getTransactionReceipt(hash);
        assertRpcSuccess(receiptResult);
        return receiptResult.getResult();
    }

    private static void destroyLogs() throws IOException {
        FileUtils.deleteDirectory(new File(System.getProperty("user.dir") + "/logs"));
    }
}
