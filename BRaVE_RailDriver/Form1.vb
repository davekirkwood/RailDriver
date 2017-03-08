
Imports System
Imports System.ComponentModel
Imports System.Threading
Imports System.Windows.Forms
Imports System.Net.Sockets
Imports System.Net

Public Class Form1
    Implements PIEHid32Net.PIEDataHandler
    Implements PIEHid32Net.PIEErrorHandler

    Dim devices() As PIEHid32Net.PIEDevice
    Dim selecteddevice As Integer

    Dim sendClient As New UdpClient
    Dim recvClient As New UdpClient(RECEIVE_PORT)
    Dim ipAddr As IPAddress
    Const IP_ADDRESS As String = "127.0.0.1"
    Const SEND_PORT As Integer = 59983
    Const RECEIVE_PORT As Integer = 59982
    Const RECEIVE_PORT_REMOTE = 59981

    Dim running As Boolean
    Dim lock As Object

    Private receiveThread As Thread
    Private sendThread As Thread

    Private Sub ReceiveTask()

        tbOutput("Waiting", TextBox2)

        While (running)
            Try

                Dim receiveBytes As [Byte]() = recvClient.Receive(New System.Net.IPEndPoint(ipAddr, RECEIVE_PORT_REMOTE))

                Dim inputStr As String
                inputStr = ""
                For i As Integer = 0 To receiveBytes.Length - 1
                    inputStr = inputStr + receiveBytes(i).ToString + "  "
                Next
                tbOutput(inputStr, TextBox2)

                If (receiveBytes.Length = 3) Then
                    If (receiveBytes(0) = 81) Then
                        shutdown()
                    End If
                    If (receiveBytes(0) = 83) Then
                        popDisplay(False, (receiveBytes(1) * 100) + receiveBytes(2))
                    End If
                End If
            Catch ex As Exception
                ' Ok, socket exception because of shutdown
            End Try
        End While
    End Sub

    Private Sub SendTask()
        While (running)
            Try
                '   SyncLock lock
                Dim sendBytes As Byte()
                    sendBytes = getData()
                    sendClient.Send(sendBytes, sendBytes.Length)
                '  End SyncLock

                Thread.Sleep(10)
            Catch ex As Exception
                ' Ok, socket exception because of shutdown
            End Try
        End While

    End Sub

    Public Sub New()

        lock = New Object
        running = True

        ' This call is required by the designer.
        InitializeComponent()
        initialiseComms()

        ' Add any initialization after the InitializeComponent() call.
        Form1_Load()
    End Sub

    Private Sub initialiseComms()
        ipAddr = IPAddress.Parse(IP_ADDRESS)
        sendClient.Connect(ipAddr, SEND_PORT)
        recvClient.Connect(ipAddr, RECEIVE_PORT_REMOTE)

        receiveThread = New Thread(AddressOf ReceiveTask)
        receiveThread.IsBackground = True
        receiveThread.Start()

        sendThread = New Thread(AddressOf SendTask)
        sendThread.IsBackground = True
        sendThread.Start()

    End Sub

    Dim di1 As Byte
    Dim di2 As Byte
    Dim di3 As Byte
    Dim di4 As Byte
    Dim throttle As Byte
    Dim mlj As Byte
    Dim lj As Byte

    Private Sub setData(throttle As Byte, mlj As Byte, lj As Byte, mrs As Integer, rcc As Boolean, lb4 As Boolean, key As Integer)
        tbAppend("Thr=" + throttle.ToString + " MLJ=" + mlj.ToString + " LJ=" + lj.ToString + " MRS=" + mrs.ToString + " RCC=" + rcc.ToString + " LB4=" + lb4.ToString + " Key=" + key.ToString, TextBox1)

        SyncLock lock
            di1 = 0
            di2 = 0
            di3 = 0
            di4 = 8     ' DSD isolate

            If (mrs = 1) Then
                di1 = di1 Or 4
            Else
                di1 = di1 Or 8
            End If
            If (rcc) Then
                di4 = di4 Or 64
            End If
            If (lb4) Then
                di3 = di3 Or 128
            End If
            If (key = 1) Then
                di1 = di1 Or 1
            Else
                ' di1 = di1 Or 2
            End If

            Me.throttle = throttle
            Me.mlj = mlj
            Me.lj = lj
        End SyncLock

    End Sub

    Public Function getData() As Byte()
        Dim sendBytes As Byte() = New Byte() {
                    0,
                    lj,
                    0,
                    mlj,
                    0,
                    throttle,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    di1,
                    di2,
                    di3,
                    di4
                    }
        Return sendBytes
    End Function



    Public Sub HandlePIEHidData(ByVal data() As Byte, ByVal sourceDevice As PIEHid32Net.PIEDevice, ByVal perror As Integer) Implements PIEHid32Net.PIEDataHandler.HandlePIEHidData
        Dim output As String

        output = "RailDriver In: " + sourceDevice.Pid.ToString + ", data="
        For i As Integer = 0 To sourceDevice.ReadLength - 1
            output = output + data(i).ToString + "  "
        Next
        tbOutput(output, TextBox1)

        Dim throttle As Integer
        throttle = data(2)
        ' Throttle calibration from 227-38
        throttle -= 38
        throttle /= ((227 - 38) / 10)
        throttle = 10 - throttle
        tbAppend("Throttle = " + throttle.ToString, TextBox1)

        Dim mlj As Integer
        mlj = data(4)
        mlj /= (256 / 10)
        mlj = 10 - mlj

        Dim lj As Integer
        lj = data(3)
        lj /= (256 / 10)
        lj = 10 - lj

        Dim mrs As Integer
        If (data(1) < 127) Then
            mrs = -1
        Else
            mrs = 1
        End If
        Dim rcc As Boolean
        rcc = data(3) < 70
        Dim lb4 As Boolean
        lb4 = data(6) > 150
        Dim key As Integer
        If (data(7) > 150) Then
            key = 1
        Else
            key = -1
        End If
        setData(throttle, mlj, lj, mrs, rcc, lb4, key)
    End Sub

    Public Sub HandlePIEHidError(ByVal sourceDevice As PIEHid32Net.PIEDevice, ByVal perror As Integer) Implements PIEHid32Net.PIEErrorHandler.HandlePIEHidError
    End Sub

    Delegate Sub StringArgReturningVoidDelegate([text] As String, [textBox] As TextBox)

    Private Sub tbOutput(ByVal [text] As String, ByVal [textBox] As TextBox)

        ' InvokeRequired required compares the thread ID of the  
        ' calling thread to the thread ID of the creating thread.  
        ' If these threads are different, it returns true.  
        If [textBox].InvokeRequired Then
            Dim d As New StringArgReturningVoidDelegate(AddressOf tbOutput)
            Me.Invoke(d, New Object() {[text], [textBox]})
        Else
            [textBox].Text = [text]
        End If
    End Sub

    Private Sub tbAppend(ByVal [text] As String, ByVal [textBox] As TextBox)

        ' InvokeRequired required compares the thread ID of the  
        ' calling thread to the thread ID of the creating thread.  
        ' If these threads are different, it returns true.  
        If [textBox].InvokeRequired Then
            Dim d As New StringArgReturningVoidDelegate(AddressOf tbAppend)
            Me.Invoke(d, New Object() {[text], [textBox]})
        Else
            [textBox].Text = [textBox].Text + vbCrLf + [text]
        End If
    End Sub

    Private Sub Form1_Load()

        devices = PIEHid32Net.PIEDevice.EnumeratePIE()
        Dim foundDevice As Boolean

        foundDevice = False

        If devices.Length = 0 Then
            tbOutput("Cannot find any devices", TextBox1)
        Else
            For i As Integer = 0 To devices.Length - 1

                If devices(i).HidUsagePage = 12 Then

                    Select Case devices(i).Pid
                        Case 210
                            selecteddevice = i
                            foundDevice = True
                        Case Else
                    End Select
                End If
            Next
        End If

        If (foundDevice) Then
            Dim result As Integer = devices(selecteddevice).SetupInterface()
            '   If result <> 0 Then
            '  tbOutput("Failed SetupInterface on device")
            ' Else
            '    tbOutput("Successful SetupInterface on device")
            'End If
            tbOutput("Found raildriver", TextBox1)
            popDisplay(True, 0)
            devices(selecteddevice).SetDataCallback(Me)
            devices(selecteddevice).SetErrorCallback(Me)
            devices(selecteddevice).callNever = False
            'While (True)
            ' System.Threading.Thread.Sleep(1000)
            ' End While
        Else
            tbOutput("Cannot find raildriver", TextBox1)
            System.Environment.Exit(0)
        End If


    End Sub

    Dim wdata() As Byte = New Byte() {} 'write data buffer

    Private Sub popDisplay(logo As Boolean, speed As Integer)
        'write to LED segments
        ReDim wdata(devices(selecteddevice).WriteLength - 1)
        If (logo) Then
            If selecteddevice <> -1 Then
                For i As Integer = 0 To devices(selecteddevice).WriteLength - 1
                    wdata(i) = 0
                Next

                wdata(1) = 134
                wdata(2) = 4 + 8 + 16 + 32 + 64
                wdata(3) = 4 + 8 + 16 + 64
                wdata(4) = 2 + 4 + 8 + 16 + 32
            End If
        Else
            Dim digit1 As Integer
            Dim digit2 As Integer
            Dim digit3 As Integer
            digit1 = Math.Floor(speed / 100)
            digit2 = Math.Floor((speed - (digit1 * 100)) / 10)
            digit3 = speed Mod 10
            wdata(1) = 134
            If (digit1 = 0) Then
                wdata(4) = 0
            Else
                wdata(4) = convertLedNumber(digit1)
            End If
            If (digit1 = 0 And digit2 = 0) Then
                wdata(3) = 0
            Else
                wdata(3) = convertLedNumber(digit2)
            End If
            wdata(2) = convertLedNumber(digit3)

        End If

        Dim result As Integer
        result = 404
        While (result = 404)
            result = devices(selecteddevice).WriteData(wdata)
        End While

    End Sub

    Private Function convertLedNumber(number As Integer) As Byte
        Select Case number
            Case 0
                Return 1 + 2 + 4 + 8 + 16 + 32
            Case 1
                Return 2 + 4
            Case 2
                Return 1 + 2 + 64 + 16 + 8
            Case 3
                Return 1 + 2 + 64 + 4 + 8
            Case 4
                Return 32 + 64 + 2 + 4
            Case 5
                Return 1 + 32 + 64 + 4 + 8
            Case 6
                Return 1 + 32 + 64 + 4 + 8 + 16
            Case 7
                Return 1 + 2 + 4
            Case 8
                Return 1 + 2 + 4 + 8 + 16 + 32 + 64
            Case 9
                Return 1 + 2 + 32 + 64 + 4
            Case Else
                Return 0
        End Select
    End Function

    Private Sub shutdown()
        running = False
        sendClient.Close()
        recvClient.Close()
        devices(selecteddevice).CloseInterface()
        System.Environment.Exit(0)
    End Sub

    Private Sub Form1_FormClosed(ByVal sender As System.Object, ByVal e As System.Windows.Forms.FormClosedEventArgs) Handles MyBase.FormClosed
        shutdown()
    End Sub

End Class