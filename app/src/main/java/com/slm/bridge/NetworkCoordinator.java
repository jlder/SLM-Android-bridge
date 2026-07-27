package com.slm.bridge;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiNetworkSpecifier;

final class NetworkCoordinator {
    interface Listener {
        void onNetworksChanged(Network recorder, Network internet);
        void onRecorderConnectionUnavailable();
    }

    private final ConnectivityManager connectivity;
    private final Listener listener;
    private ConnectivityManager.NetworkCallback recorderCallback;
    private ConnectivityManager.NetworkCallback cellularCallback;
    private ConnectivityManager.NetworkCallback defaultInternetCallback;
    private volatile int recorderRequestGeneration;
    private volatile Network recorderNetwork;
    private volatile Network cellularNetwork;
    private volatile Network defaultInternetNetwork;

    NetworkCoordinator(Context context, Listener listener) {
        this.connectivity = context.getSystemService(ConnectivityManager.class);
        this.listener = listener;
        registerDefaultInternetCallback();
    }

    Network recorderNetwork() { return recorderNetwork; }
    Network cellularNetwork() { return cellularNetwork; }
    Network uploadNetwork() {
        // Recorder Wi-Fi has no Internet.  Drive/server traffic must therefore
        // use a validated Internet network selected explicitly with
        // Network.openConnection(), even while the WebView process is bound to
        // the recorder Wi-Fi for the local recorder interface.
        Network cellular = cellularNetwork;
        if (isUsableInternet(cellular)) return cellular;

        Network defaultInternet = defaultInternetNetwork;
        if (isUsableInternet(defaultInternet)) return defaultInternet;

        // Android callbacks can lag while the phone is switching to recorder
        // Wi-Fi.  Query the current network list to avoid showing Server
        // Off-line when cellular/Internet is already available.
        Network discoveredCellular = findValidatedInternetNetwork(true);
        if (discoveredCellular != null) return discoveredCellular;
        return findValidatedInternetNetwork(false);
    }

    void connect(String ssid, String password) {
        disconnectRecorder();
        final int requestGeneration = ++recorderRequestGeneration;
        ensureCellularRequest();

        WifiNetworkSpecifier.Builder wifi = new WifiNetworkSpecifier.Builder().setSsid(ssid);
        if (password != null && !password.isEmpty()) wifi.setWpa2Passphrase(password);
        NetworkRequest recorderRequest = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(wifi.build())
                .build();

        recorderCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                if (requestGeneration != recorderRequestGeneration) return;
                recorderNetwork = network;
                // WebView does not expose an API for selecting a Network. Bind the
                // process to the recorder Wi-Fi so its UI loads from the recorder;
                // uploads still use uploadNetwork().openConnection explicitly.
                connectivity.bindProcessToNetwork(network);
                notifyListener();
            }
            @Override public void onLost(Network network) {
                if (requestGeneration != recorderRequestGeneration) return;
                if (network.equals(recorderNetwork)) {
                    recorderNetwork = null;
                    connectivity.bindProcessToNetwork(null);
                    // Once the recorder disappears, return to the phone's
                    // normal Internet routing and stop holding a special
                    // cellular request open.
                    releaseCellularRequest();
                }
                notifyListener();
            }
            @Override public void onUnavailable() {
                if (requestGeneration != recorderRequestGeneration) return;
                recorderNetwork = null;
                connectivity.bindProcessToNetwork(null);
                releaseCellularRequest();
                listener.onRecorderConnectionUnavailable();
                notifyListener();
            }
        };
        // Android may need time for the user to confirm the recorder network
        // and for the phone to finish switching away from its normal Wi-Fi.
        // Keep the timeout short enough that a stopped recorder returns promptly.
        connectivity.requestNetwork(recorderRequest, recorderCallback, 30_000);

    }

    void disconnectRecorder() {
        recorderRequestGeneration++;
        if (recorderCallback != null) {
            try { connectivity.unregisterNetworkCallback(recorderCallback); } catch (RuntimeException ignored) {}
        }
        recorderCallback = null;
        recorderNetwork = null;
        connectivity.bindProcessToNetwork(null);
        releaseCellularRequest();
        notifyListener();
    }

    void stop() {
        disconnectRecorder();
        releaseCellularRequest();
        if (defaultInternetCallback != null) {
            try { connectivity.unregisterNetworkCallback(defaultInternetCallback); } catch (RuntimeException ignored) {}
        }
        defaultInternetCallback = null;
        defaultInternetNetwork = null;
        notifyListener();
    }

    private void ensureCellularRequest() {
        if (cellularCallback != null) return;
        NetworkRequest cellularRequest = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();
        cellularCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                updateCellularInternet(network);
            }
            @Override public void onCapabilitiesChanged(Network network,
                                                          NetworkCapabilities capabilities) {
                if (isValidatedInternet(capabilities)) cellularNetwork = network;
                else if (network.equals(cellularNetwork)) cellularNetwork = null;
                notifyListener();
            }
            @Override public void onLost(Network network) {
                if (network.equals(cellularNetwork)) cellularNetwork = null;
                notifyListener();
            }
            @Override public void onUnavailable() {
                cellularNetwork = null;
                notifyListener();
            }
        };
        connectivity.requestNetwork(cellularRequest, cellularCallback);
    }

    private void updateCellularInternet(Network network) {
        NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(network);
        cellularNetwork = isValidatedInternet(capabilities) ? network : null;
        notifyListener();
    }

    private void releaseCellularRequest() {
        if (cellularCallback != null) {
            try { connectivity.unregisterNetworkCallback(cellularCallback); } catch (RuntimeException ignored) {}
        }
        cellularCallback = null;
        cellularNetwork = null;
    }

    private void registerDefaultInternetCallback() {
        defaultInternetCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                updateDefaultInternet(network);
            }

            @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                if (isValidatedInternet(capabilities)) defaultInternetNetwork = network;
                else if (network.equals(defaultInternetNetwork)) defaultInternetNetwork = null;
                notifyListener();
            }

            @Override public void onLost(Network network) {
                if (network.equals(defaultInternetNetwork)) defaultInternetNetwork = null;
                notifyListener();
            }
        };
        connectivity.registerDefaultNetworkCallback(defaultInternetCallback);
    }

    private void updateDefaultInternet(Network network) {
        NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(network);
        defaultInternetNetwork = isValidatedInternet(capabilities) ? network : null;
        notifyListener();
    }

    private Network findValidatedInternetNetwork(boolean cellularOnly) {
        try {
            Network[] networks = connectivity.getAllNetworks();
            if (networks == null) return null;
            for (Network network : networks) {
                if (!isUsableInternet(network)) continue;
                NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(network);
                if (cellularOnly
                        && (capabilities == null
                        || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))) {
                    continue;
                }
                return network;
            }
        } catch (RuntimeException ignored) {
            // Keep status conservative if Android cannot enumerate networks.
        }
        return null;
    }

    private boolean isUsableInternet(Network network) {
        if (network == null) return false;
        Network recorder = recorderNetwork;
        if (recorder != null && network.equals(recorder)) return false;
        return isValidatedInternet(connectivity.getNetworkCapabilities(network));
    }

    private static boolean isValidatedInternet(NetworkCapabilities capabilities) {
        return capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    private void notifyListener() { listener.onNetworksChanged(recorderNetwork, uploadNetwork()); }
}
